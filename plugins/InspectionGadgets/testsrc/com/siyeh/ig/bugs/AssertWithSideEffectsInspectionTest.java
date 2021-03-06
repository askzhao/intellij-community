package com.siyeh.ig.bugs;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.siyeh.ig.IGInspectionTestCase;

public class AssertWithSideEffectsInspectionTest extends IGInspectionTestCase {
  @Override
  protected Sdk getTestProjectSdk() {
    // uses SQL
    return PsiTestUtil.addJdkAnnotations(IdeaTestUtil.getMockJdk17());
  }

  public void test() {
    doTest("com/siyeh/igtest/bugs/assert_with_side_effects",
           new AssertWithSideEffectsInspection());
  }
}