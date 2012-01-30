/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.folding;

import com.intellij.codeInsight.folding.JavaCodeFoldingSettings;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.containers.hash.HashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.lexer.TokenSets;
import org.jetbrains.plugins.groovy.lang.parser.GroovyElementTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrString;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrStringInjection;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinitionBody;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.util.GrStringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author ilyas
 */
public class GroovyFoldingBuilder implements FoldingBuilder, GroovyElementTypes, DumbAware {

  @NotNull
  public FoldingDescriptor[] buildFoldRegions(@NotNull ASTNode node, @NotNull Document document) {
    List<FoldingDescriptor> descriptors = new ArrayList<FoldingDescriptor>();
    appendDescriptors(node.getPsi(), descriptors, new HashSet<PsiElement>());
    return descriptors.toArray(new FoldingDescriptor[descriptors.size()]);
  }

  private static void appendDescriptors(PsiElement element, List<FoldingDescriptor> descriptors, Set<PsiElement> usedComments) {
    ASTNode node = element.getNode();
    if (node == null) return;
    IElementType type = node.getElementType();

    if (BLOCK_SET.contains(type) && !isSingleClassBody(element) || type == CLOSABLE_BLOCK) {
      if (isMultiline(element)) {
        descriptors.add(new FoldingDescriptor(node, node.getTextRange()));
      }
    }
    // comments
    if ((type.equals(mML_COMMENT) || type.equals(GROOVY_DOC_COMMENT)) &&
        isMultiline(element) &&
        isWellEndedComment(element)) {
      descriptors.add(new FoldingDescriptor(node, node.getTextRange()));
    }

    if (type.equals(mSL_COMMENT) && !usedComments.contains(element)) {
      usedComments.add(element);
      PsiElement end = null;
      for (PsiElement current = element.getNextSibling(); current != null; current = current.getNextSibling()) {
        IElementType elementType = current.getNode().getElementType();
        if (elementType == mSL_COMMENT) {
          end = current;
          usedComments.add(current);
          continue;
        }
        if (TokenSets.WHITE_SPACES_SET.contains(elementType)) {
          continue;
        }
        break;
      }
      if (end != null) {
        descriptors
          .add(new FoldingDescriptor(element, new TextRange(element.getTextRange().getStartOffset(), end.getTextRange().getEndOffset())));
      }
    }

    //multiline strings
    addFoldingForStrings(descriptors, node);

    Set<PsiElement> newUsedComments = new HashSet<PsiElement>();
    for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendDescriptors(child, descriptors, newUsedComments);
    }

    if (element instanceof GroovyFile) {
      GroovyFile file = (GroovyFile)element;
      addFoldingsForImports(descriptors, file);
    }
  }

  private static boolean isSingleClassBody(PsiElement element) {
    if (element instanceof GrTypeDefinitionBody) {
      final PsiElement parent = element.getParent();
      if (parent instanceof GrTypeDefinition &&
          !((GrTypeDefinition)parent).isAnonymous() &&
          ((GrTypeDefinition)parent).getContainingClass() == null) {
        final PsiFile file = element.getContainingFile();
        if (file instanceof GroovyFile) {
          return ((GroovyFile)file).getClasses().length == 1;
        }
      }
    }
    return false;
  }

  private static void addFoldingForStrings(List<FoldingDescriptor> descriptors, ASTNode node) {
    if (!isMultiLineStringLiteral(node)) return;

    if (!node.getElementType().equals(GSTRING) && !node.getElementType().equals(REGEX)) {
      descriptors.add(new FoldingDescriptor(node, node.getTextRange()));
      return;
    }

    final GrString grString = (GrString)node.getPsi();
    if (grString == null) return;

    final GrStringInjection[] injections = grString.getInjections();
    if (injections.length == 0) {
      descriptors.add(new FoldingDescriptor(node, node.getTextRange()));
      return;
    }
    final String start_quote = GrStringUtil.getStartQuote(node.getText());
    final String end_quote = GrStringUtil.getEndQuote(node.getText());
    final FoldingGroup group = FoldingGroup.newGroup("GString");
    final TextRange nodeRange = node.getTextRange();
    int startOffset = nodeRange.getStartOffset();

    GrStringInjection injection = injections[0];
    TextRange injectionRange = injection.getTextRange();
    if (startOffset + 1 < injectionRange.getStartOffset()) {
      descriptors.add(new GStringFoldingDescriptor(node, startOffset, injectionRange.getStartOffset(), group, start_quote));
    }

    final String placeholder = " ";
    startOffset = injectionRange.getEndOffset();
    for (int i = 1; i < injections.length; i++) {
      injection = injections[i];
      injectionRange = injection.getTextRange();
      final int endOffset = injectionRange.getStartOffset();
      if (endOffset - startOffset >= 2) {
        descriptors.add(new GStringFoldingDescriptor(injection.getNode().getTreePrev(), startOffset, endOffset, group, placeholder));
      }
      startOffset = injectionRange.getEndOffset();
    }
    if (startOffset + 1 < nodeRange.getEndOffset()) {
      descriptors.add(new GStringFoldingDescriptor(node.getLastChildNode(), startOffset, nodeRange.getEndOffset(), group, end_quote));
    }
  }

  private static class GStringFoldingDescriptor extends FoldingDescriptor {
    private final String myPlaceholderText;

    private GStringFoldingDescriptor(@NotNull ASTNode node, int start, int end, @Nullable FoldingGroup group, String placeholderText) {
      super(node, new TextRange(start, end), group);
      myPlaceholderText = placeholderText;
    }

    @Override
    public String getPlaceholderText() {
      return myPlaceholderText;
    }
  }

  private static void addFoldingsForImports(final List<FoldingDescriptor> descriptors, final GroovyFile file) {
    final GrImportStatement[] statements = file.getImportStatements();
    if (statements.length > 1) {
      PsiElement first = statements[0];
      while (first != null) {
        PsiElement marker = first;
        PsiElement next = first.getNextSibling();
        while (next instanceof GrImportStatement || next instanceof LeafPsiElement) {
          if (next instanceof GrImportStatement) marker = next;
          next = next.getNextSibling();
        }
        if (marker != first) {
          int start = first.getTextRange().getStartOffset();
          int end = marker.getTextRange().getEndOffset();
          int tail = "import ".length();
          if (start + tail < end) {
            descriptors.add(new FoldingDescriptor(first.getNode(), new TextRange(start + tail, end)));
          }
        }
        while (!(next instanceof GrImportStatement) && next != null) next = next.getNextSibling();
        first = next;
      }
    }
  }

  private static boolean isWellEndedComment(PsiElement element) {
    return  element.getText().endsWith("*/");
  }

  private static boolean isWellEndedString(PsiElement element) {
    final String text = element.getText();

    if (!text.endsWith("'''") && !text.endsWith("\"\"\"") && !text.endsWith("/") && !text.endsWith("/$")) return false;


    final IElementType type = element.getNode().getElementType();
    if (TokenSets.STRING_LITERAL_SET.contains(type)) return true;

    final PsiElement lastChild = element.getLastChild();
    if (lastChild == null) return false;

    final IElementType lastType = lastChild.getNode().getElementType();
    if (type == GSTRING) return lastType == mGSTRING_END;
    if (type == REGEX) return lastType == mREGEX_END || lastChild == mDOLLAR_SLASH_REGEX_END;

    return false;
  }

  private static boolean isMultiline(PsiElement element) {
    String text = element.getText();
    return text.contains("\n") || text.contains("\r") || text.contains("\r\n");
  }

  public String getPlaceholderText(@NotNull ASTNode node) {
    final IElementType elemType = node.getElementType();
    if (BLOCK_SET.contains(elemType) || elemType == CLOSABLE_BLOCK) {
      return "{...}";
    }
    if (elemType.equals(mML_COMMENT)) {
      return "/*...*/";
    }
    if (elemType.equals(GROOVY_DOC_COMMENT)) {
      return "/**...*/";
    }
    if (IMPORT_STATEMENT.equals(elemType)) {
      return "...";
    }
    if (isMultiLineStringLiteral(node)) {
      final String start_quote = GrStringUtil.getStartQuote(node.getText());
      final String end_quote = GrStringUtil.getEndQuote(node.getText());
      return start_quote + "..." + end_quote;
    }
    return null;
  }

  public boolean isCollapsedByDefault(@NotNull ASTNode node) {
    final JavaCodeFoldingSettings settings = JavaCodeFoldingSettings.getInstance();
    if ( node.getElementType() == IMPORT_STATEMENT ){
      return settings.isCollapseImports();
    }

    if (node.getElementType() == GROOVY_DOC_COMMENT) {
      return settings.isCollapseJavadocs();
    }

    if ((node.getElementType() == OPEN_BLOCK || node.getElementType() == CONSTRUCTOR_BODY) && node.getTreeParent().getElementType() == METHOD_DEFINITION) {
      return settings.isCollapseMethods();
    }

    if (node.getElementType() == CLOSABLE_BLOCK) {
      return settings.isCollapseAnonymousClasses();
    }

    if (node.getElementType() == CLASS_BODY) {
      final PsiElement parent = node.getPsi().getParent();
      if (parent instanceof PsiClass) {
        if (parent instanceof PsiAnonymousClass) {
          return settings.isCollapseAnonymousClasses();
        }
        if (((PsiClass)parent).getContainingClass() != null) {
          return settings.isCollapseInnerClasses();
        }
      }
    }

    if (node.getElementType() == mSL_COMMENT) {
      return settings.isCollapseEndOfLineComments();
    }

    return false;
  }

  private static boolean isMultiLineStringLiteral(ASTNode node) {
    return (TokenSets.STRING_LITERAL_SET.contains(node.getElementType()) ||
            node.getElementType().equals(GSTRING) ||
            node.getElementType().equals(REGEX)) &&
           isMultiline(node.getPsi()) &&
           isWellEndedString(node.getPsi());
  }
}
