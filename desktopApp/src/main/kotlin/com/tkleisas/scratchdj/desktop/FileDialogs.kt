package com.tkleisas.scratchdj.desktop

import java.io.File
import javax.swing.JFileChooser

object FileDialogs {
    fun chooseDirectory(): File? {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Add music folder"
            currentDirectory = File(System.getProperty("user.home"))
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }
}
