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
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
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
            return AxlResolver.resolveName(ref, name);
        }
        PsiElement owner = qualifier instanceof AxlReferenceExpression q ? q.getReference() == null ? null
                : q.getReference().resolve() : null;
        if (owner instanceof PsiPackage pkg) {
            PsiElement member = AxlResolver.inPackage(ref, pkg, name);
            return member == null ? List.of() : List.of(member);
        }
        PsiClass cls = AxlResolver.asClass(owner);
        if (cls != null) {
            List<PsiElement> members = AxlResolver.overloadsFor(ref, AxlResolver.members(cls, name));
            PsiClass nested = members.isEmpty() ? cls.findInnerClassByName(name, true) : null;
            return nested != null ? List.of(nested) : members;
        }
        PsiClass type = AxlResolver.typeOf(qualifier);
        return type == null ? List.of() : AxlResolver.overloadsFor(ref, AxlResolver.members(type, name));
    }

    @Override
    public Object @NotNull [] getVariants() {
        AxlReferenceExpression ref = getElement();
        PsiElement qualifier = ref.qualifier();
        Map<String, LookupElement> variants = new LinkedHashMap<>();
        if (qualifier == null) {
            boolean typeOnly = isInType(ref);
            for (PsiElement visible : AxlResolver.visibleDeclarations(ref)) {
                if (typeOnly && !isType(visible)) {
                    continue;
                }
                if (visible instanceof AxlNamedElement named && named.getName() != null) {
                    variants.putIfAbsent(named.getName(), LookupElementBuilder.create(named).withIcon(icon(named)));
                } else if (visible instanceof AxlImport imp) {
                    variants.putIfAbsent(imp.simpleName(), LookupElementBuilder.create(imp.simpleName()));
                }
            }
            return variants.values().toArray();
        }
        PsiElement owner = qualifier instanceof AxlReferenceExpression q && q.getReference() != null ? q.getReference().resolve() : null;
        if (owner instanceof PsiPackage pkg) {
            return packageVariants(pkg, ref.getResolveScope());
        }
        PsiClass named = AxlResolver.asClass(owner);
        boolean self = isThis(qualifier);
        boolean statics = named != null && !self;
        PsiClass cls = named != null ? named : AxlResolver.typeOf(qualifier);
        if (cls == null) {
            return new Object[0];
        }
        List<LookupElement> members = new ArrayList<>();
        for (PsiMethod method : cls.getAllMethods()) {
            if (visible(method, self) && !method.isConstructor() && statics == method.hasModifierProperty(PsiModifier.STATIC)) {
                members.add(LookupElementBuilder.create(method, method.getName())
                        .withTailText(method.getParameterList().getText(), true)
                        .withTypeText(method.getReturnType() == null ? "" : method.getReturnType().getPresentableText()));
            }
        }
        for (PsiField field : cls.getAllFields()) {
            if (visible(field, self) && statics == field.hasModifierProperty(PsiModifier.STATIC)) {
                members.add(LookupElementBuilder.create(field, field.getName()).withTypeText(field.getType().getPresentableText()));
            }
        }
        return members.toArray();
    }

    /** In a written type, such as after `:` or `->` in a declaration, or after `is`. */
    public static boolean isInType(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, AxlType.class) != null;
    }

    private static boolean isType(PsiElement declaration) {
        return declaration instanceof AxlTypeDeclaration || declaration instanceof AxlImport imp && imp.kind() == AxlImport.Kind.TYPE;
    }

    /** `this` stands for the enclosing class's instance, whose private members are visible too. */
    private static boolean isThis(PsiElement element) {
        return element instanceof AxlReferenceExpression ref && ref.qualifier() == null && ref.referenceName().equals("this");
    }

    private static boolean visible(PsiMember member, boolean self) {
        return member.hasModifierProperty(PsiModifier.PUBLIC) || self && member.hasModifierProperty(PsiModifier.PRIVATE);
    }

    /** Subpackages and classes after a package, as in `import java.util.Li`. */
    private static Object[] packageVariants(PsiPackage pkg, GlobalSearchScope scope) {
        List<LookupElement> result = new ArrayList<>();
        for (PsiPackage sub : pkg.getSubPackages(scope)) {
            result.add(LookupElementBuilder.create(sub, String.valueOf(sub.getName())));
        }
        for (PsiClass cls : pkg.getClasses(scope)) {
            result.add(LookupElementBuilder.create(cls, String.valueOf(cls.getName())));
        }
        return result.toArray();
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

    /** Only for a single target: a call that several overloads may take must not be renamed with one of them. */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        // `this` does not spell its class's name, so renaming the class must leave it alone.
        if (isThis(getElement())) {
            return false;
        }
        ResolveResult[] results = multiResolve(false);
        if (results.length != 1) {
            return false;
        }
        PsiElement target = results[0].getElement();
        return getElement().getManager().areElementsEquivalent(target, element)
                || target instanceof PsiMember member && getElement().getManager().areElementsEquivalent(member.getNavigationElement(), element);
    }
}
