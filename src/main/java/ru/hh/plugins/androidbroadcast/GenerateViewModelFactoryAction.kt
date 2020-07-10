package ru.hh.plugins.androidbroadcast

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.actions.generate.KotlinGenerateActionBase
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.idea.util.findAnnotation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import ru.hh.plugins.androidbroadcast.ext.reformatWithCodeStyle


class GenerateViewModelFactoryAction : KotlinGenerateActionBase() {

    companion object {
        private const val VIEW_MODEL_FQNAME = "androidx.lifecycle.ViewModel"
        private const val ASSISTED_ANNOTATION_FQNAME = "com.squareup.inject.assisted.Assisted"
        private const val ASSISTED_INJECT_ANNOTATION_FQNAME = "com.squareup.inject.assisted.AssistedInject"
        private const val ASSISTED_INJECT_FACTORY_FQNAME = "$ASSISTED_INJECT_ANNOTATION_FQNAME.Factory"

        private const val VIEW_MODEL_PROVIDER_FACTORY_FQNAME = "androidx.lifecycle.ViewModelProvider.Factory"
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        // Hey, user just clicked on our menu item!

        (psiFile as? KtFile)?.let { ktPsiFile ->
            // Get Kotlin's class from PsiFile
            val ktClass = ktPsiFile.findDescendantOfType<KtClass>() ?: return

            // Find all value parameters from class's primary constructor,
            // which were annotated with @Assisted annotation
            val assistedAnnotationFqName = FqName(ASSISTED_ANNOTATION_FQNAME)
            val valueParameters = ktClass.primaryConstructor
                ?.valueParameters
                ?.filter { it.findAnnotation(assistedAnnotationFqName) != null }
                ?: return

            // Convert parameters into two lists:
            // 1. names + types ("name: Type", "itemId: String", "param2: Long", etc)
            // 2. only names ("name", "itemId", "param2", etc)
            val paramsWithTypes = valueParameters.map { "${it.name} : ${it.type()}" }
            val params = valueParameters.map { "${it.name}" }

            // Get target texts for interface and class
            val innerFactoryText = getFactoryInterfaceText(paramsWithTypes, ktClass)
            val viewModelFactoryText = getViewModelFactoryClassText(ktClass, paramsWithTypes, params)

            // Convert text into PSI structures
            val ktPsiFactory = KtPsiFactory(project)
            val innerFactoryClass = ktPsiFactory.createClass(innerFactoryText)
            val viewModelFactoryClass = ktPsiFactory.createClass(viewModelFactoryText)

            // === It's time to modify code! ===

            // Setup environment to code modifying
            project.executeWriteCommand("GenerateViewModelFactoryAction") {
                // Insert interface into original class
                ktClass.getOrCreateBody().also { body ->
                    body.addBefore(innerFactoryClass, body.rBrace)
                }
                // Add ViewModelFactory class into file
                ktPsiFile.add(viewModelFactoryClass)

                // Split our fqnames into beautiful imports
                ktClass.containingKtFile.commitAndUnblockDocument()
                ShortenReferences.DEFAULT.process(ktPsiFile)

                // Apply code style to file for make all tabs and line breaks right
                ktClass.containingKtFile.reformatWithCodeStyle()
            }
        }
    }

    override fun isValidForClass(targetClass: KtClassOrObject): Boolean {
        return targetClass is KtClass
                && InheritanceUtil.isInheritor(targetClass.toLightClass(), VIEW_MODEL_FQNAME)
                && targetClass.primaryConstructor?.findAnnotation(FqName(ASSISTED_INJECT_ANNOTATION_FQNAME)) != null
    }


    private fun getFactoryInterfaceText(
        paramsWithTypes: List<String>,
        ktClass: KtClass
    ): String {
        return """
        @$ASSISTED_INJECT_FACTORY_FQNAME
        interface Factory {
            fun create(${paramsWithTypes.joinToString()}): ${ktClass.name}
        }   
        """
    }

    private fun getViewModelFactoryClassText(
        ktClass: KtClass,
        paramsWithTypes: List<String>,
        params: List<String>
    ): String {
        return """
        class ${ktClass.name}Factory @$ASSISTED_INJECT_ANNOTATION_FQNAME constructor(
            ${paramsWithTypes.joinToString(separator = ",\n") { parameter ->
            "@param:$ASSISTED_ANNOTATION_FQNAME private val $parameter"
        }},
            private val viewModelFactory: ${ktClass.name}.Factory
        ): $VIEW_MODEL_PROVIDER_FACTORY_FQNAME {
        
            @Suppress("UNCHECKED_CAST")
            override fun <T : $VIEW_MODEL_FQNAME?> create(modelClass: Class<T>): T {
                require(modelClass == ${ktClass.name}::class.java)
                return viewModelFactory.create(${params.joinToString()}) as T
            }
        
            @$ASSISTED_INJECT_FACTORY_FQNAME
            interface Factory {
                fun create(${paramsWithTypes.joinToString()}): ${ktClass.name}Factory
            }
        
        }    
        """
    }

}