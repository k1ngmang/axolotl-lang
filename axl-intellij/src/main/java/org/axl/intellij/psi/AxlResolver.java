package org.axl.intellij.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.axl.intellij.parser.AxlElementTypes;
import org.jetbrains.annotations.Nullable;

/** Name lookup in .axl files: scopes, top-level declarations, imports and Java classes. */
public final class AxlResolver {

    private static final Map<String, String> BUILTIN_TYPES = Map.of(
            "Int", "java.lang.Integer", "Long", "java.lang.Long", "Boolean", "java.lang.Boolean", "Double", "java.lang.Double");

    private AxlResolver() {}

    static @Nullable PsiElement resolveName(PsiElement place, String name) {
        for (PsiElement local : localDeclarations(place)) {
            if (local instanceof AxlNamedElement named && name.equals(named.getName())) {
                return named;
            }
        }
        AxlFile file = file(place);
        if (file == null) {
            return null;
        }
        for (AxlFunction function : file.functions()) {
            if (name.equals(function.getName())) {
                return function;
            }
        }
        for (AxlVariable value : file.values()) {
            if (name.equals(value.getName())) {
                return value;
            }
        }
        boolean inImport = com.intellij.psi.util.PsiTreeUtil.getParentOfType(place, AxlImport.class) != null;
        for (AxlImport imp : file.imports()) {
            if (inImport || !imp.simpleName().equals(name)) {
                continue;
            }
            String qualified = imp.qualifiedName();
            if (imp.kind() == AxlImport.Kind.TYPE) {
                return findClass(place, qualified);
            }
            PsiClass owner = findClass(place, qualified.substring(0, Math.max(0, qualified.lastIndexOf('.'))));
            List<PsiElement> members = owner == null ? List.of() : members(owner, name);
            return members.isEmpty() ? null : members.getFirst();
        }
        String pkg = file.packageName();
        String builtin = BUILTIN_TYPES.get(name);
        for (String candidate : List.of(builtin != null ? builtin : pkg.isEmpty() ? name : pkg + "." + name,
                "java.lang." + name, "axl.lang." + name)) {
            PsiClass cls = findClass(place, candidate);
            if (cls != null) {
                return cls;
            }
        }
        return JavaPsiFacade.getInstance(place.getProject()).findPackage(name);
    }

    static @Nullable PsiElement inPackage(PsiElement place, PsiPackage pkg, String name) {
        String qualified = pkg.getQualifiedName().isEmpty() ? name : pkg.getQualifiedName() + "." + name;
        PsiClass cls = findClass(place, qualified);
        return cls != null ? cls : JavaPsiFacade.getInstance(place.getProject()).findPackage(qualified);
    }

    /** Methods and fields of a class by name; members of Axolotl files resolve to their declarations. */
    static List<PsiElement> members(PsiClass cls, String name) {
        LinkedHashSet<PsiElement> result = new LinkedHashSet<>();
        for (PsiMethod method : cls.findMethodsByName(name, true)) {
            result.add(source(method));
        }
        PsiField field = cls.findFieldByName(name, true);
        if (field != null) {
            result.add(source(field));
        }
        return new ArrayList<>(result);
    }

    private static PsiElement source(PsiMember member) {
        PsiElement navigation = member.getNavigationElement();
        return navigation instanceof AxlNamedElement ? navigation : member;
    }

    /** The class of an expression's value, when it can be told without type inference. */
    static @Nullable PsiClass typeOf(PsiElement expression) {
        if (expression.getNode().getElementType() == AxlElementTypes.PARENTHESIZED) {
            PsiElement inner = expression.getFirstChild().getNextSibling();
            return inner == null ? null : typeOf(inner);
        }
        if (expression.getNode().getElementType() == AxlElementTypes.NOT_NULL) {
            return typeOf(expression.getFirstChild());
        }
        if (expression.getNode().getElementType() == AxlElementTypes.LITERAL && expression.getText().startsWith("\"")) {
            return findClass(expression, "java.lang.String");
        }
        PsiElement target = null;
        if (expression instanceof AxlReferenceExpression ref && ref.getReference() != null) {
            target = ref.getReference().resolve();
        } else if (expression.getNode().getElementType() == AxlElementTypes.CALL
                && expression.getFirstChild() instanceof AxlReferenceExpression callee && callee.getReference() != null) {
            target = callee.getReference().resolve();
            if (target instanceof PsiClass constructed) {
                return constructed;
            }
        }
        return switch (target) {
            case AxlVariable variable -> variable.type() == null ? null : classOf(variable.type());
            case AxlFunction function -> function.returnType() == null ? null : classOf(function.returnType());
            case PsiField field -> classOf(field.getType());
            case PsiMethod method -> classOf(method.getReturnType());
            case null, default -> null;
        };
    }

    static @Nullable PsiClass classOf(AxlType type) {
        String builtin = BUILTIN_TYPES.get(type.name());
        if (builtin != null) {
            return findClass(type, builtin);
        }
        AxlReferenceExpression reference = type.reference();
        PsiElement target = reference == null || reference.getReference() == null ? null : reference.getReference().resolve();
        return target instanceof PsiClass cls ? cls : null;
    }

    private static @Nullable PsiClass classOf(@Nullable PsiType type) {
        return type instanceof PsiClassType classType ? classType.resolve() : null;
    }

    /** Locals, parameters, top-level declarations and imports visible at a place, innermost first. */
    static List<PsiElement> visibleDeclarations(PsiElement place) {
        List<PsiElement> result = new ArrayList<>(localDeclarations(place));
        AxlFile file = file(place);
        if (file != null) {
            result.addAll(file.functions());
            result.addAll(file.values());
            result.addAll(file.imports());
        }
        return result;
    }

    /** Declarations in enclosing blocks before the place, lambda and function parameters. */
    private static List<PsiElement> localDeclarations(PsiElement place) {
        List<PsiElement> result = new ArrayList<>();
        PsiElement child = place;
        for (PsiElement parent = place.getParent(); parent != null && !(parent instanceof AxlFile); parent = parent.getParent()) {
            var type = parent.getNode().getElementType();
            if (type == AxlElementTypes.BLOCK || type == AxlElementTypes.LAMBDA || type == AxlElementTypes.ATTEMPT) {
                List<PsiElement> before = new ArrayList<>();
                for (PsiElement sibling = parent.getFirstChild(); sibling != null && sibling != child; sibling = sibling.getNextSibling()) {
                    if (sibling instanceof AxlVariable) {
                        before.addFirst(sibling);
                    }
                }
                result.addAll(before);
            } else if (parent instanceof AxlFunction function) {
                result.addAll(function.parameters());
            }
            child = parent;
        }
        return result;
    }

    static @Nullable PsiClass findClass(PsiElement place, String qualifiedName) {
        if (qualifiedName.isEmpty()) {
            return null;
        }
        GlobalSearchScope scope = place.getResolveScope();
        return JavaPsiFacade.getInstance(place.getProject()).findClass(qualifiedName, scope);
    }

    private static @Nullable AxlFile file(PsiElement place) {
        return place.getContainingFile() instanceof AxlFile file ? file : null;
    }
}
