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

package org.jetbrains.kotlin.cli.common.script

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.script.ScriptErrorManager
import kotlin.script.dependencies.ScriptReport

class CliScriptErrorManager(private val messageCollector: MessageCollector) : ScriptErrorManager {
    override val lastErrors: List<ScriptReport>
        get() = TODO("not implemented")

    override fun setErrors(scriptFile: VirtualFile, errors: List<ScriptReport>) {
        errors.forEach {
            messageCollector.report(it.severity.convertSeverity(), it.message, location(scriptFile, it.position))
        }
    }

    private fun location(scriptFile: VirtualFile, position: ScriptReport.Position?): CompilerMessageLocation? {
        if (position == null) return CompilerMessageLocation.create(scriptFile.path)

        return CompilerMessageLocation.create(scriptFile.path, position.startLine, position.startColumn, null)
    }

    private fun ScriptReport.Severity.convertSeverity(): CompilerMessageSeverity = when(this) {
        ScriptReport.Severity.ERROR -> CompilerMessageSeverity.ERROR
        ScriptReport.Severity.WARNING -> CompilerMessageSeverity.WARNING
        ScriptReport.Severity.INFO -> CompilerMessageSeverity.INFO
        ScriptReport.Severity.DEBUG -> CompilerMessageSeverity.LOGGING
    }
}

