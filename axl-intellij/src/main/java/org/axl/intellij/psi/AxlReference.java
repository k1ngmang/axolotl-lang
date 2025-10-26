package org.axl.intellij.psi;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.util.IncorrectOperationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/** Resolves names with the same rules as the translator, plus Java classes for qualified access. */
public final class AxlReference extends PsiPolyVariantReferenceBase<AxlReferenceExpression> {

    AxlReference(AxlReferenceExpression element, TextRange range) {
        super(element, range);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        List<PsiElement> targets = resolveTargets();
        return targets.stream().map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
    }

    private List<PsiElement> resolveTargets() {
        AxlReferenceExpression ref = getElement();
        String name = ref.referenceName();
        PsiElement qualifier = ref.qualifier();
        if (qualifier == null) {
            PsiElement target = AxlResolver.resolveName(ref, name);
            return target == null ? List.of() : List.of(target);
        }
        PsiElement owner = qualifier instanceof AxlReferenceExpression q ? q.getReference() == null ? null
                : q.getReference().resolve() : null;
        if (owner instanceof PsiPackage pkg) {
            PsiElement member = AxlResolver.inPackage(ref, pkg, name);
            return member == null ? List.of() : List.of(member);
        }
        if (owner instanceof PsiClass cls) {
            return AxlResolver.members(cls, name);
        }
        PsiClass type = AxlResolver.typeOf(qualifier);
        return type == null ? List.of() : AxlResolver.members(type, name);
    }

    @Override
    public Object @NotNull [] getVariants() {
        AxlReferenceExpression ref = getElement();
        PsiElement qualifier = ref.qualifier();
        Map<String, LookupElement> variants = new LinkedHashMap<>();
        if (qualifier == null) {
            for (PsiElement visible : AxlResolver.visibleDeclarations(ref)) {
                if (visible instanceof AxlNamedElement named && named.getName() != null) {
                    variants.putIfAbsent(named.getName(), LookupElementBuilder.create(named).withIcon(icon(named)));
                } else if (visible instanceof AxlImport imp) {
                    variants.putIfAbsent(imp.simpleName(), LookupElementBuilder.create(imp.simpleName()));
                }
            }
            return variants.values().toArray();
        }
        PsiElement owner = qualifier instanceof AxlReferenceExpression q && q.getReference() != null ? q.getReference().resolve() : null;
        boolean statics = owner instanceof PsiClass;
        PsiClass cls = owner instanceof PsiClass c ? c : AxlResolver.typeOf(qualifier);
        if (cls == null) {
            return new Object[0];
        }
        List<LookupElement> members = new ArrayList<>();
        for (PsiMethod method : cls.getAllMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor() && statics == method.hasModifierProperty(PsiModifier.STATIC)) {
                members.add(LookupElementBuilder.create(method, method.getName())
                        .withTailText(method.getParameterList().getText(), true)
                        .withTypeText(method.getReturnType() == null ? "" : method.getReturnType().getPresentableText()));
            }
        }
        for (PsiField field : cls.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.PUBLIC) && statics == field.hasModifierProperty(PsiModifier.STATIC)) {
                members.add(LookupElementBuilder.create(field, field.getName()).withTypeText(field.getType().getPresentableText()));
            }
        }
        return members.toArray();
    }

    private static javax.swing.Icon icon(AxlNamedElement element) {
        return element.getPresentation() == null ? null : element.getPresentation().getIcon(false);
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newName) throws IncorrectOperationException {
        PsiElement identifier = getElement().identifier();
        if (identifier != null) {
            identifier.replace(AxlPsiFactory.identifier(getElement().getProject(), newName));
        }
        return getElement();
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        for (ResolveResult result : multiResolve(false)) {
            PsiElement target = result.getElement();
            if (getElement().getManager().areElementsEquivalent(target, element)
                    || target instanceof PsiMember member && getElement().getManager().areElementsEquivalent(member.getNavigationElement(), element)) {
                return true;
            }
        }
        return false;
    }
}
