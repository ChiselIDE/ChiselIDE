
package com.intellij.find.replaceInProject;

import com.intellij.find.*;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.openapi.actionSystem.DataConstants;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Factory;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.usageView.*;
import com.intellij.usages.*;
import com.intellij.usages.UsageViewManager;
import com.intellij.usages.UsageView;
import com.intellij.util.Processor;

import javax.swing.*;
import java.util.Set;
import java.util.Iterator;
import java.util.HashSet;

public class ReplaceInProjectManager implements ProjectComponent {
  private Project myProject;
  private boolean myIsFindInProgress = false;

  public static ReplaceInProjectManager getInstance(Project project) {
    return project.getComponent(ReplaceInProjectManager.class);
  }

  public void projectOpened() {}

  public void projectClosed() {}

  public String getComponentName() {
    return "ReplaceInProjectManager";
  }

  public void initComponent() {}

  public void disposeComponent() {}

  public ReplaceInProjectManager(Project project) {
    myProject = project;
  }

  static class ReplaceContext {
    private com.intellij.usages.UsageView usageView;
    private FindModel findModel;
    private Set<Usage> excludedSet;

    ReplaceContext(com.intellij.usages.UsageView _usageView, FindModel _findModel) {
      usageView = _usageView;
      findModel = _findModel;
    }

    public FindModel getFindModel() {
      return findModel;
    }

    public com.intellij.usages.UsageView getUsageView() {
      return usageView;
    }

    public Set<Usage> getExcludedSet() {
      if (excludedSet == null) excludedSet = usageView.getExcludedUsages();
      return excludedSet;
    }
  }

  public void replaceInProject(DataContext dataContext) {
    final FindManager findManager = FindManager.getInstance(myProject);
    final FindModel findModel = (FindModel) findManager.getFindInProjectModel().clone();
    findModel.setReplaceState(true);
    FindInProjectUtil.setDirectoryName(findModel, dataContext);

    Editor editor = (Editor)dataContext.getData(DataConstants.EDITOR);
    if (editor != null){
      String s = editor.getSelectionModel().getSelectedText();
      if (s != null && (s.indexOf("\r") == -1) && (s.indexOf("\n") == -1)){
        findModel.setStringToFind(s);
      }
    }
    if (!findManager.showFindDialog(findModel)){
      return;
    }
    final PsiDirectory psiDirectory = FindInProjectUtil.getPsiDirectory(findModel, myProject);
    if (!findModel.isProjectScope() && psiDirectory == null && findModel.getModuleName()==null){
      return;
    }

    com.intellij.usages.UsageViewManager manager = myProject.getComponent(com.intellij.usages.UsageViewManager.class);

    if (manager!=null) {
      final UsageViewPresentation presentation = FindInProjectUtil.setupViewPresentation(true, findModel);
      final FindUsagesProcessPresentation processPresentation = FindInProjectUtil.setupProcessPresentation(
        myProject, true, presentation
      );

      final ReplaceContext context[] = new ReplaceContext[1];

      manager.searchAndShowUsages(
        new UsageTarget[] { new FindInProjectUtil.StringUsageTarget(findModel.getStringToFind()) },
        new Factory<UsageSearcher>() {
          public UsageSearcher create() {
            return new UsageSearcher() {

            public void generate(final Processor<Usage> processor) {
              myIsFindInProgress = true;

              FindInProjectUtil.findUsages(
                findModel,
                psiDirectory,
                myProject,
                new FindInProjectUtil.AsyncFindUsagesProcessListener2ProcessorAdapter(processor)
              );
              myIsFindInProgress = false;
            }
          };
          }
        },
        processPresentation,
        presentation,
        new UsageViewManager.UsageViewStateListener() {
          public void usageViewCreated(UsageView usageView) {
            context[0] = new ReplaceContext(usageView,findModel);
            addReplaceActions(context[0]);
          }

        public void findingUsagesFinished() {
          if (context[0]!=null && findManager.getFindInProjectModel().isPromptOnReplace()){
            SwingUtilities.invokeLater(new Runnable() {
              public void run() {
                replaceWithPrompt(context[0]);
              }
            });
          }
        }
      }
      );
    }
  }

  private void replaceWithPrompt(final ReplaceContext replaceContext) {
    final Set<Usage> _usages = replaceContext.getUsageView().getUsages();

    if (FindInProjectUtil.hasReadOnlyUsages(_usages)){
      WindowManager.getInstance().getStatusBar(myProject).setInfo("Occurrences found in read-only files");
      return;
    }

    final Usage[] usages;
    _usages.toArray(usages = new Usage[_usages.size()]);
    
    //usageView.expandAll();
    for(int i = 0; i < usages.length; ++i){
      final Usage usage = usages[i];
      final UsageInfo usageInfo = ((UsageInfo2UsageAdapter)usage).getUsageInfo();

      final PsiFile psiFile = usageInfo.getElement().getContainingFile();
      if (!psiFile.isWritable()) continue;

      Runnable selectOnEditorRunnable = new Runnable() {
        public void run() {
          final VirtualFile virtualFile = psiFile.getVirtualFile();

          if (virtualFile != null &&
              ApplicationManager.getApplication().runReadAction(
                new Computable<Boolean>() {
                  public Boolean compute() {
                    return virtualFile.isValid() ? Boolean.TRUE : Boolean.FALSE;
                  }
                }
              ).booleanValue()) {

            if (usage.isValid()) {
              usage.highlightInEditor();
              replaceContext.getUsageView().selectUsages(new Usage[]{usage});
            }
          }
        }
      };

      CommandProcessor.getInstance().executeCommand(myProject, selectOnEditorRunnable, "Select on Editor", null);
      String title = "Replace Usage " + (i+1) + " of " + usages.length + " Found";
      int result = FindManager.getInstance(myProject).showPromptDialog(replaceContext.getFindModel(), title);

      if (result == PromptResult.CANCEL){
        return;
      }
      if (result == PromptResult.SKIP){
        continue;
      }

      final int currentNumber = i;
      if (result == PromptResult.OK){
        Runnable runnable = new Runnable() {
          public void run() {
            doReplace(replaceContext, usage);
            replaceContext.getUsageView().removeUsage(usage);
          }
        };
        CommandProcessor.getInstance().executeCommand(myProject, runnable, "Replace", null);
        if ((i + 1) == usages.length){
          replaceContext.getUsageView().close();
          return;
        }
      }

      if (result == PromptResult.ALL_IN_THIS_FILE){
        final int[] nextNumber = new int[1];

        Runnable runnable = new Runnable() {
          public void run() {
            int j = currentNumber;

            for(; j < usages.length; j++){
              final Usage usage = usages[j];
              final UsageInfo usageInfo = ((UsageInfo2UsageAdapter)usage).getUsageInfo();

              PsiFile otherPsiFile = usageInfo.getElement().getContainingFile();
              if (!otherPsiFile.equals(psiFile)){
                break;
              }
              doReplace(replaceContext, usage);
              replaceContext.getUsageView().removeUsage(usage);
            }
            if (j == usages.length){
              replaceContext.getUsageView().close();
            }
            nextNumber[0] = j;
          }
        };

        CommandProcessor.getInstance().executeCommand(myProject, runnable, "Replace", null);
        i = nextNumber[0] - 1;
      }

      if (result == PromptResult.ALL_FILES) {
        CommandProcessor.getInstance().executeCommand(
            myProject, new Runnable() {
            public void run() {
              for(int i = 0;i < usages.length;++i){
                doReplace(replaceContext, usages[i]);
              }
              replaceContext.getUsageView().close();
            }
          },
          "Replace",
          null
        );
        break;
      }
    }
  }

  private void addReplaceActions(final ReplaceContext replaceContext) {
    final Runnable replaceRunnable = new Runnable() {
      public void run() {
        doReplace(replaceContext, replaceContext.getUsageView().getUsages());
      }
    };
    replaceContext.getUsageView().addPerformOperationAction(replaceRunnable, "Replace All",null,"Do Replace All", SystemInfo.isMac ? 0 : 'D');

    final Runnable replaceSelectedRunnable = new Runnable() {
      public void run() {
        doReplaceSelected(replaceContext);
      }
    };

    replaceContext.getUsageView().addButtonToLowerPane(
      replaceSelectedRunnable,
      "Replace Selected",
      SystemInfo.isMac ? 0 : 'l'
    );
  }

  private void doReplace(final ReplaceContext replaceContext, Set<Usage> usages) {
    for(Iterator<Usage> i = usages.iterator(); i.hasNext();){
      doReplace(replaceContext, i.next());
    }
  }

  private void doReplace(final ReplaceContext replaceContext, final Usage usage) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        if (replaceContext.getExcludedSet().contains(usage)){
          return;
        }

        RangeMarker marker = ((UsageInfo2UsageAdapter)usage).getRangeMarker();
        Document document = marker.getDocument();
        if (!document.isWritable()) return;

        final int textOffset = marker.getStartOffset();
        if (textOffset < 0 || textOffset >= document.getTextLength()){
          return;
        }
        final int textEndOffset = marker.getEndOffset();
        if (textEndOffset < 0 || textOffset > document.getTextLength()){
          return;
        }
        FindManager findManager = FindManager.getInstance(myProject);
        final CharSequence foundString = document.getCharsSequence().subSequence(textOffset, textEndOffset);
        FindResult findResult = findManager.findString(foundString, 0, replaceContext.getFindModel());
        if (findResult == null || !findResult.isStringFound()){
          return;
        }
        String stringToReplace = findManager.getStringToReplace(foundString.toString(), replaceContext.getFindModel());
        document.replaceString(textOffset, textEndOffset, stringToReplace);
      }
    });
  }

  private void doReplaceSelected(final ReplaceContext replaceContext) {
    final Set<Usage> selectedUsages = replaceContext.getUsageView().getSelectedUsages();
    if(selectedUsages == null){
      return;
    }

    Set<VirtualFile> readOnlyFiles = null;
    for(Iterator<Usage> i = selectedUsages.iterator();i.hasNext();) {
      final VirtualFile file = ((UsageInfo2UsageAdapter)i.next()).getFile();

      if (!file.isWritable()) {
        if (readOnlyFiles == null) readOnlyFiles = new HashSet<VirtualFile>();
        readOnlyFiles.add(file);
      }
    }

    if (readOnlyFiles != null) {
      ReadonlyStatusHandler.getInstance(myProject).ensureFilesWritable(
        readOnlyFiles.toArray(new VirtualFile[readOnlyFiles.size()] )
      );
    }

    if (FindInProjectUtil.hasReadOnlyUsages(selectedUsages)){
      int result = Messages.showOkCancelDialog(
        replaceContext.getUsageView().getComponent(),
        "Occurrences found in read-only files.\n" +
          "The operation will not affect them.\n" +
          "Continue anyway?",
        "Read-only Files Found",
        Messages.getWarningIcon()
      );
      if (result != 0){
        return;
      }
    }

    CommandProcessor.getInstance().executeCommand(
        myProject, new Runnable() {
        public void run() {
          doReplace(replaceContext, selectedUsages);
          for (Iterator<Usage> i = selectedUsages.iterator(); i.hasNext(); ) {
            replaceContext.getUsageView().removeUsage(i.next());
          }

          if (replaceContext.getUsageView().getUsages().size() == 0){
            replaceContext.getUsageView().close();
            return;
          }
          replaceContext.getUsageView().getComponent().requestFocus();
        }
      },
      "Replace",
      null
    );
  }

  public boolean isWorkInProgress() {
    return myIsFindInProgress;
  }

  public boolean isEnabled () {
    return !myIsFindInProgress && !FindInProjectManager.getInstance(myProject).isWorkInProgress();
  }

}