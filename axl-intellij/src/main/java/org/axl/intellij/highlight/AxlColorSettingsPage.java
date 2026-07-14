package org.axl.intellij.highlight;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import java.util.Map;
import javax.swing.Icon;
import org.axl.intellij.AxlIcons;
import org.jetbrains.annotations.NotNull;

public final class AxlColorSettingsPage implements ColorSettingsPage {

    private static final AttributesDescriptor[] DESCRIPTORS = {
        new AttributesDescriptor("Keyword", AxlSyntaxHighlighter.KEYWORD),
        new AttributesDescriptor("String", AxlSyntaxHighlighter.STRING),
        new AttributesDescriptor("Number", AxlSyntaxHighlighter.NUMBER),
        new AttributesDescriptor("Comments//Line comment", AxlSyntaxHighlighter.LINE_COMMENT),
        new AttributesDescriptor("Comments//Block comment", AxlSyntaxHighlighter.BLOCK_COMMENT),
        new AttributesDescriptor("Operators//Null-safe operators ?. and ?:", AxlSyntaxHighlighter.NULL_OPERATOR),
        new AttributesDescriptor("Operators//Other operators", AxlSyntaxHighlighter.OPERATOR),
        new AttributesDescriptor("Braces and parentheses//Braces", AxlSyntaxHighlighter.BRACES),
        new AttributesDescriptor("Braces and parentheses//Parentheses", AxlSyntaxHighlighter.PARENTHESES),
        new AttributesDescriptor("Function declaration", AxlSyntaxHighlighter.FUNCTION_DECLARATION),
        new AttributesDescriptor("Type declaration", AxlSyntaxHighlighter.TYPE_DECLARATION),
        new AttributesDescriptor("Enum constant", AxlSyntaxHighlighter.ENUM_CONSTANT),
        new AttributesDescriptor("Annotation", AxlSyntaxHighlighter.ANNOTATION),
        new AttributesDescriptor("Bad character", AxlSyntaxHighlighter.BAD_CHARACTER),
    };

    @Override
    public Icon getIcon() {
        return AxlIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new AxlSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                package app
                import fn util.Parse.doubled

                /* Greets a user. */
                fn <fn>greet</fn>(name: String?) -> String =
                    "Hello, " + (name?.trim() ?: "stranger")

                sealed interface <type>Shape</type>

                data class <type>Circle</type>(val radius: Double) : Shape

                enum <type>Color</type> { <const>RED</const>, <const>GREEN</const> }

                class <type>Counter</type>(val name: String, var step: Int) {
                    var count: Int = 0
                    fn next() -> Int {
                        count += step
                        return count
                    }

                    <annotation>@Override</annotation>
                    fn toString() -> String = name + " " + count
                }

                fn <fn>area</fn>(shape: Shape) -> Double = when (shape) {
                    is Circle -> 3.14 * shape.radius() * shape.radius()
                    else -> 0.0
                }

                fn <fn>main</fn>() {
                    var count = 0
                    for (x in java.util.List.of(1, 2)) {
                        count += x
                    }
                    println(greet(null))   // Hello, stranger
                    println(doubled("21"))
                }
                """;
    }

    @Override
    public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.of("fn", AxlSyntaxHighlighter.FUNCTION_DECLARATION, "type", AxlSyntaxHighlighter.TYPE_DECLARATION,
                "const", AxlSyntaxHighlighter.ENUM_CONSTANT, "annotation", AxlSyntaxHighlighter.ANNOTATION);
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Axolotl";
    }
}
