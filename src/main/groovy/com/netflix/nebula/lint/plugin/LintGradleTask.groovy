/*
 * Copyright 2015-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.nebula.lint.plugin

import com.netflix.nebula.lint.GradleLintInfoBrokerAction
import com.netflix.nebula.lint.GradleLintPatchAction
import com.netflix.nebula.lint.GradleLintViolationAction
import com.netflix.nebula.lint.GradleViolation
import com.netflix.nebula.lint.StyledTextService
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import static com.netflix.nebula.lint.StyledTextService.Styling.*

class LintGradleTask extends DefaultTask {
    List<GradleLintViolationAction> listeners = []

    @TaskAction
    void lint() {
        def violations = new LintService().lint(project).violations
                .unique { v1, v2 -> v1.is(v2) ? 0 : 1 }

        (listeners + new GradleLintPatchAction(project) + new GradleLintInfoBrokerAction(project) + consoleOutputAction).each {
            it.lintFinished(violations)
        }
    }

    final def consoleOutputAction = new GradleLintViolationAction() {
        @Override
        void lintFinished(Collection<GradleViolation> violations) {
            def totalBySeverity = [(GradleViolation.Level.Warning): 0, (GradleViolation.Level.Error): 0] +
                    violations.countBy { it.level }

            def textOutput = new StyledTextService(getServices())

            if (totalBySeverity[GradleViolation.Level.Error] > 0 || totalBySeverity[GradleViolation.Level.Warning] > 0) {
                textOutput.withStyle(Bold).text('\nThis project contains lint violations. ')
                textOutput.println('A complete listing of the violations follows. ')

                if (totalBySeverity[GradleViolation.Level.Error]) {
                    textOutput.text('Because some were serious, the overall build status has been changed to ')
                            .withStyle(Red).println("FAILED\n")
                } else {
                    textOutput.println('Because none were serious, the build\'s overall status was unaffected.\n')
                }
            }

            violations.groupBy { it.file }.each { buildFile, violationsByFile ->
                def buildFilePath = project.rootDir.toURI().relativize(buildFile.toURI()).toString()

                violationsByFile.each { v ->
                    switch (v.level as GradleViolation.Level) {
                        case GradleViolation.Level.Warning:
                            textOutput.withStyle(Red).text('warning'.padRight(10))
                            break
                        case GradleViolation.Level.Error:
                            textOutput.withStyle(Red).text('error'.padRight(10))
                            break
                        case GradleViolation.Level.Trivial:
                            textOutput.withStyle(Yellow).text('trivial'.padRight(10))
                            break
                        case GradleViolation.Level.Info:
                            textOutput.withStyle(Yellow).text('info'.padRight(10))
                            break
                    }

                    textOutput.text(v.rule.ruleId.padRight(35))

                    textOutput.withStyle(Yellow).print(v.message)
                    if(v.fixes.isEmpty()) {
                        textOutput.withStyle(Yellow).print(' (no auto-fix available)')
                    }
                    textOutput.println()
                    
                    if (v.lineNumber)
                        textOutput.withStyle(Bold).println(buildFilePath + ':' + v.lineNumber)
                    if (v.sourceLine)
                        textOutput.println("$v.sourceLine")

                    textOutput.println() // extra space between violations
                }

                def errors = violationsByFile.count { it.level == GradleViolation.Level.Error }
                def warnings = violationsByFile.count { it.level == GradleViolation.Level.Warning }
                if (errors + warnings > 0) {
                    textOutput.withStyle(Red)
                            .println("\u2716 ${buildFilePath}: ${errors + warnings} problem${errors + warnings == 1 ? '' : 's'} ($errors error${errors == 1 ? '' : 's'}, $warnings warning${warnings == 1 ? '' : 's'})\n".toString())
                }
            }

            if (totalBySeverity[GradleViolation.Level.Error] > 0 || totalBySeverity[GradleViolation.Level.Warning] > 0) {
                textOutput.text("To apply fixes automatically, run ").withStyle(Bold).text("fixGradleLint")
                textOutput.println(", review, and commit the changes.\n")

                if (totalBySeverity.error)
                    throw new LintCheckFailedException() // fail the whole build
            }
        }
    }
}
