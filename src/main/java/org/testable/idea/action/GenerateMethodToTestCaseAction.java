package org.testable.idea.action;

import com.google.common.collect.Lists;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.ClassUtil;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.testable.idea.helper.GenerationTestCaseHelper;

import javax.lang.model.element.Modifier;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement;
import static com.intellij.testIntegration.createTest.CreateTestUtils.computeTestRoots;

/**
 * @author jim
 */
public class GenerateMethodToTestCaseAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GenerateMethodToTestCaseAction.class);

    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getRequiredData(CommonDataKeys.PROJECT);

        PsiElement element = (PsiElement) e.getDataContext().getData("psi.Element");

        if (!(element instanceof PsiMethod)) {
            return;
        }
        PsiMethod psiMethod = (PsiMethod) element;
        PsiClass containingClass = psiMethod.getContainingClass();
        if (containingClass == null) {
            return;
        }

        Module srcModule = findModuleForPsiElement(element);
        if (srcModule == null) {
            return;
        }
        List<VirtualFile> testRootUrls = computeTestRoots(srcModule);

        String testJavaFile = containingClass.getQualifiedName() + "Test";
        String mockJavaFile = containingClass.getQualifiedName() + "Mock";

        PsiClass testClass = JavaPsiFacade.getInstance(project).findClass(testJavaFile, GlobalSearchScope.projectScope(project));
        if (testClass == null) {
            testClass = JavaPsiFacade.getInstance(project).findClass(mockJavaFile, GlobalSearchScope.projectScope(project));
        }

        if (testClass == null) {
            GenerationTestCaseHelper.getInstance().generateTest(containingClass, Lists.newArrayList(psiMethod));
            return;
        }

        Boolean inTestFolder = Optional.ofNullable(testClass.getContainingFile())
                .map(PsiFile::getVirtualFile)
                .map(v -> testRootUrls.stream().anyMatch(z -> isChild(z, v)))
                .orElse(false);

        if (!inTestFolder) {
            LOG.debug("The test class not in test folder");
            return;
        }

        if (methodExist(testClass, psiMethod)) {
            LOG.debug("The test class mock method was exist");
            return;
        }

        insertMockMethod(testClass, psiMethod);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        final Editor editor = e.getData(CommonDataKeys.EDITOR);

        // Set visibility only in case of existing project and editor and if a selection exists
        e.getPresentation().setEnabledAndVisible( project != null
                && editor != null
                && editor.getSelectionModel().hasSelection() );
    }

    private void insertMockMethod(PsiClass testClass, PsiMethod selectedMethod) {
        Project project = testClass.getProject();
        PsiClass selectedClass = selectedMethod.getContainingClass();
        if (selectedClass == null) {
            return;
        }
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            MethodSpec methodSpec = GenerationTestCaseHelper.getInstance().transformMethod(selectedMethod, selectedClass.getQualifiedName());

            if (hasInnerMockClass(testClass)) {
                PsiElement insert = factory.createMethodFromText(methodSpec.toString(), selectedMethod);
                Arrays.stream(testClass.getInnerClasses())
                        .filter(v -> StringUtils.equals(v.getName(), "Mock"))
                        .findFirst()
                        .ifPresent(v -> v.add(insert));
            } else {
                PsiElement insert = factory.createClassFromText(
                        TypeSpec.classBuilder("Mock")
                                .addMethod(methodSpec)
                                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .build().toString(), selectedMethod);
                testClass.add(insert);
            }
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(testClass);
            String msg = MessageFormat.format("The {0} mock-method was add to {1} success.", selectedMethod.getName(), testClass.getName());
            NotificationGroupManager.getInstance().getNotificationGroup("Custom Notification Group")
                    .createNotification(msg, NotificationType.INFORMATION)
                    .notify(project);
            LOG.debug("The test class mock created success");
        });
    }

    private boolean isChild(VirtualFile parent, VirtualFile child) {
        return Paths.get(child.getPath()).startsWith(Paths.get(parent.getPath()));
    }

    private boolean methodExist(PsiClass testClass, PsiMethod selectedMethod) {
        return Arrays.stream(testClass.getInnerClasses())
                .filter(v -> StringUtils.equals(v.getName(), "Mock"))
                .findFirst()
                .map(v -> methodExist0(v, selectedMethod))
                .orElse(false);
    }

    private boolean methodExist0(PsiClass innerMockClass, PsiMethod selectedMethod) {
        String asmMethodSignature = ClassUtil.getAsmMethodSignature(selectedMethod);
        return Arrays.stream(innerMockClass.getMethods())
                .filter(v -> StringUtils.equals(v.getName(), selectedMethod.getName()))
                .anyMatch(v -> StringUtils.equals(ClassUtil.getAsmMethodSignature(v), asmMethodSignature));
    }

    private boolean hasInnerMockClass(PsiClass testClass) {
        return Arrays.stream(testClass.getInnerClasses()).anyMatch(v -> StringUtils.equals(v.getName(), "Mock"));
    }
}
