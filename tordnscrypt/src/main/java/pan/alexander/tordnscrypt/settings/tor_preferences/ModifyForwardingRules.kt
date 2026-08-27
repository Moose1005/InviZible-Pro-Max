/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2025 by Garmatin Oleksandr invizible.soft@gmail.com
 */

package pan.alexander.tordnscrypt.settings.tor_preferences

import android.content.Context
import pan.alexander.tordnscrypt.App
import pan.alexander.tordnscrypt.modules.ModulesRestarter
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.utils.enums.ModuleState
import pan.alexander.tordnscrypt.utils.filemanager.ForwardingRulesLock
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import java.io.File
import java.io.PrintWriter

class ModifyForwardingRules(private val context: Context,
                            private val lineToReplaceTo: String) {

    private val pathVars = App.instance.daggerComponent.getPathVars().get()
    private val forwardingRulesFile: String = pathVars.dnsCryptForwardingRulesPath
    private val localForwardingRulesFile: String = pathVars.dnsCryptLocalForwardingRulesPath
    private val cacheDir: String = pathVars.appDataDir + "/cache/"
    private val tempFile: String = "$cacheDir/tmpForwardingRules.txt"
    private val lineToFindRegExp = Regex("^onion +127.0.0.1:\\d+$")

    fun getRunnable() : Runnable {
        return Runnable {
            try {
                val dir = File(cacheDir)
                if (!dir.isDirectory) {
                    dir.mkdirs()
                }

                replaceLineInFile(forwardingRulesFile, tempFile)
                replaceLineInFile(localForwardingRulesFile, tempFile)

                restartDNSCryptIfRequired()

            } catch (e: java.lang.Exception) {
                loge("ImportRules", e)
            }
        }
    }

    // SECURITY (audit #13): this snapshot-write-copyBack races TailscaleAutoConfig, which appends
    // and removes the "ts.net 127.0.0.1:1053" line from its own executor. Without a shared lock,
    // a snapshot taken before that append is copied back afterwards and silently erases the rule,
    // sending .ts.net lookups to the public upstream resolver and exposing tailnet hostnames.
    private fun replaceLineInFile(inputFilePath: String, tmpOutputFilePath: String) = synchronized(ForwardingRulesLock.LOCK) {
        try {

            val inputFile = File(inputFilePath)
            val outputFile = File(tmpOutputFilePath)

            if (inputFile.isFile) {
                outputFile.printWriter().use {printWriter ->
                    writeToFile(inputFile, printWriter)
                }

                outputFile.copyTo(inputFile, true)
                outputFile.delete()
            }

        } catch (e: Exception) {
            loge("ImportRules", e)
        }
    }

    private fun writeToFile(inputFile: File, printWriter: PrintWriter) {

        try {

            inputFile.bufferedReader().use {
                var line = it.readLine()?.trim()
                while (line != null && !Thread.currentThread().isInterrupted) {

                    if (line.matches(lineToFindRegExp)) {
                        printWriter.println(lineToReplaceTo)
                    } else if (line.isNotEmpty()) {
                        printWriter.println(line)
                    }
                    line = it.readLine()?.trim()
                }
            }

        } catch (e: Exception) {
            loge("ModifyForwardingRules", e)
        }
    }

    private fun restartDNSCryptIfRequired() {
        if (ModulesStatus.getInstance().dnsCryptState == ModuleState.RUNNING) {
            ModulesRestarter.restartDNSCrypt(context)
        }
    }
}