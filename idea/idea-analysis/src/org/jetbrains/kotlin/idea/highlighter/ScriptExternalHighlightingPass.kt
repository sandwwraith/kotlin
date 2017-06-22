/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeHighlighting.Pass
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.HighlightSeverity.*
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.script.ScriptErrorManager
import kotlin.script.dependencies.ScriptReport

class ScriptExternalHighlightingPass(
        private val file: KtFile,
        document: Document
) : TextEditorHighlightingPass(file.project, document), DumbAware {
    override fun doCollectInformation(progress: ProgressIndicator) = Unit

    override fun doApplyInformationToEditor() {
        val document = document ?: return

        val annotations = getErrors().mapNotNull {
            (message, severity, position) ->
            val (startOffset, endOffset) = computeOffsets(document, position) ?: return@mapNotNull null
            Annotation(
                    startOffset,
                    endOffset,
                    severity.convertSeverity() ?: return@mapNotNull null,
                    message,
                    null
            )
        }

        val infos = annotations.map { HighlightInfo.fromAnnotation(it) }
        UpdateHighlightersUtil.setHighlightersToEditor(myProject, myDocument!!, 0, file.textLength, infos, colorsScheme, id)
    }

    // TODO_R: get errors correctly
    private fun getErrors() = myProject.service<ScriptErrorManager>().lastErrors

    // TODO_R: use API to get endoffset of the error
    private fun computeOffsets(document: Document, position: ScriptReport.Position?): Pair<Int, Int>? {
        val (line, col) = position ?: return null
        val offset = document.getLineStartOffset(line) + col
        if (offset < 0) return null

        val lineEnd = document.getLineEndOffset(line)
        val indexOfFirst = document.getText(TextRange(offset, lineEnd)).indexOfFirst { it.isWhitespace() }
        val endOffset = if (indexOfFirst >= 0) offset + indexOfFirst else lineEnd
        return offset to endOffset
    }

    private fun ScriptReport.Severity.convertSeverity(): HighlightSeverity? {
        return when (this) {
            ScriptReport.Severity.ERROR -> ERROR
            ScriptReport.Severity.WARNING -> WARNING
            ScriptReport.Severity.INFO -> INFORMATION
            else -> null
        }
    }

    class Factory(project: Project, registrar: TextEditorHighlightingPassRegistrar)
        : AbstractProjectComponent(project), TextEditorHighlightingPassFactory {
        init {
            registrar.registerTextEditorHighlightingPass(this, TextEditorHighlightingPassRegistrar.Anchor.BEFORE, Pass.UPDATE_FOLDING, false, false)
        }

        override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
            if (file !is KtFile) return null
            return ScriptExternalHighlightingPass(file, editor.document)
        }
    }
}
