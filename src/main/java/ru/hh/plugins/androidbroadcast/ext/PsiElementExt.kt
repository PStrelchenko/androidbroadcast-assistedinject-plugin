package ru.hh.plugins.androidbroadcast.ext

import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager


fun PsiElement.reformatWithCodeStyle() {
    CodeStyleManager.getInstance(this.project).reformat(this)
}