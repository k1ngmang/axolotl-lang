package org.axl.intellij.highlight;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiFile;
import java.util.ArrayList;
import java.util.List;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;

/** Top-level functions, values, data classes and interfaces of a file, and the fields of the latter two. */
public final class AxlStructureViewFactory implements PsiStructureViewFactory {

    @Override
    public StructureViewBuilder getStructureViewBuilder(@NotNull PsiFile psiFile) {
        return new TreeBasedStructureViewBuilder() {
            @Override
            public @NotNull StructureViewModel createStructureViewModel(Editor editor) {
                return new StructureViewModelBase(psiFile, editor, new Element((NavigatablePsiElement) psiFile))
                        .withSuitableClasses(AxlFunction.class, AxlVariable.class, AxlTypeDeclaration.class);
            }
        };
    }

    private static final class Element implements StructureViewTreeElement {
        private final NavigatablePsiElement element;

        Element(NavigatablePsiElement element) {
            this.element = element;
        }

        @Override
        public Object getValue() {
            return element;
        }

        @Override
        public @NotNull ItemPresentation getPresentation() {
            ItemPresentation presentation = element.getPresentation();
            return presentation != null ? presentation : new com.intellij.navigation.ColoredItemPresentation() {
                @Override
                public String getPresentableText() {
                    return element.getName();
                }

                @Override
                public javax.swing.Icon getIcon(boolean unused) {
                    return null;
                }

                @Override
                public com.intellij.openapi.editor.colors.TextAttributesKey getTextAttributesKey() {
                    return null;
                }
            };
        }

        @Override
        public TreeElement @NotNull [] getChildren() {
            List<TreeElement> children = new ArrayList<>();
            if (element instanceof AxlTypeDeclaration declaration) {
                declaration.fields().forEach(field -> children.add(new Element(field)));
            } else if (element instanceof AxlFile file) {
                for (var child : file.getChildren()) {
                    if (child instanceof AxlFunction || child instanceof AxlVariable || child instanceof AxlTypeDeclaration) {
                        children.add(new Element((NavigatablePsiElement) child));
                    }
                }
            }
            return children.toArray(TreeElement[]::new);
        }

        @Override
        public void navigate(boolean requestFocus) {
            element.navigate(requestFocus);
        }

        @Override
        public boolean canNavigate() {
            return element.canNavigate();
        }

        @Override
        public boolean canNavigateToSource() {
            return element.canNavigateToSource();
        }
    }
}
