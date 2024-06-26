// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl.text

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.ApiStatus.Internal

@Internal
/**
 * Executed only for psi-aware text editor.
 * Extension must be implemented only by a core plugin.
 */
interface TextEditorInitializer {
  suspend fun initializeEditor(
    project: Project,
    file: VirtualFile,
    document: Document,
    editorSupplier: suspend () -> EditorEx,
    highlighterReady: suspend () -> Unit,
  )
}