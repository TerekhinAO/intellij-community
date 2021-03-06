// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.refactoring.invertBoolean;

import com.intellij.lang.LanguageNamesValidation;
import com.intellij.lang.findUsages.DescriptiveNameUtil;
import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.help.HelpManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.ui.RefactoringDialog;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.usageView.UsageViewUtil;

import javax.swing.*;

/**
 * @author ven
 */
public class InvertBooleanDialog extends RefactoringDialog {
  private JTextField myNameField;
  private JPanel myPanel;
  private JLabel myLabel;
  private JLabel myCaptionLabel;

  private final PsiElement myElement;

  public InvertBooleanDialog(final PsiElement element) {
    super(element.getProject(), false);
    myElement = element;
    final String name = myElement instanceof PsiNamedElement ? ((PsiNamedElement)myElement).getName() : myElement.getText();
    myNameField.setText(name);
    myLabel.setLabelFor(myNameField);
    final String typeString = UsageViewUtil.getType(myElement);
    myLabel.setText(RefactoringBundle.message("invert.boolean.name.of.inverted.element", typeString));
    myCaptionLabel.setText(RefactoringBundle.message("invert.0.1",
                                                     typeString,
                                                     DescriptiveNameUtil.getDescriptiveName(myElement)));

    setTitle(InvertBooleanHandler.REFACTORING_NAME);
    init();
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myNameField;
  }

  @Override
  protected void doAction() {
    Project project = myElement.getProject();
    final String name = myNameField.getText().trim();
    final NamesValidator namesValidator = LanguageNamesValidation.INSTANCE.forLanguage(myElement.getLanguage());
    if (namesValidator != null && !namesValidator.isIdentifier(name, myProject)) {
      CommonRefactoringUtil.showErrorMessage(InvertBooleanHandler.REFACTORING_NAME,
                                             RefactoringBundle.message("please.enter.a.valid.name.for.inverted.element",
                                                                       UsageViewUtil.getType(myElement)),
                                             InvertBooleanHandler.INVERT_BOOLEAN_HELP_ID, project);
      return;
    }

    invokeRefactoring(new InvertBooleanProcessor(myElement, name));
  }

  @Override
  protected void doHelpAction() {
    HelpManager.getInstance().invokeHelp(InvertBooleanHandler.INVERT_BOOLEAN_HELP_ID);
  }

  @Override
  protected JComponent createCenterPanel() {
    return myPanel;
  }
}
