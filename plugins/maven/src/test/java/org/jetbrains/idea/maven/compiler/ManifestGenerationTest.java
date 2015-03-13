/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.idea.maven.compiler;

import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.testFramework.VfsTestUtil;
import junit.framework.AssertionFailedError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * @author Vladislav.Soroka
 * @since 5/23/2014
 */
public class ManifestGenerationTest extends MavenCompilingTestCase {
  public void testBasic() throws Exception {
    importProject("<groupId>test</groupId>" +
                  "<artifactId>project</artifactId>" +
                  "<version>1</version>");

    compileModules("project");

    assertUnorderedLinesWithFile(getProjectPath() + "/target/MANIFEST.MF",
                                 "Manifest-Version: 1.0\n" +
                                 "Build-Jdk: " + extractJdkVersion(getModule("project")) + "\n" +
                                 "Built-By: " + System.getProperty("user.name") + "\n" +
                                 "Created-By: " + ApplicationNamesInfo.getInstance().getFullProductName());
  }

  public void testClasspathEntry() throws Exception {
    importProject("<groupId>test</groupId>" +
                  "<artifactId>project</artifactId>" +
                  "<version>1</version>" +

                  "<dependencies>" +
                  "  <dependency>" +
                  "    <groupId>test</groupId>" +
                  "    <artifactId>other-project</artifactId>" +
                  "    <version>1</version>" +
                  "  </dependency>" +
                  "</dependencies>" +

                  "<build>" +
                  "    <plugins>" +
                  "        <plugin>" +
                  "            <artifactId>maven-jar-plugin</artifactId>" +
                  "            <version>2.4</version>" +
                  "            <configuration>" +
                  "                <archive>" +
                  "                    <manifest>" +
                  "                        <addClasspath>true</addClasspath>" +
                  "                        <classpathPrefix>lib</classpathPrefix>" +
                  "                    </manifest>" +
                  "                </archive>" +
                  "            </configuration>" +
                  "        </plugin>" +
                  "    </plugins>" +
                  "</build>");

    compileModules("project");

    assertUnorderedLinesWithFile(getProjectPath() + "/target/MANIFEST.MF",
                                 "Manifest-Version: 1.0\n" +
                                 "Class-Path: lib/other-project-1.jar\n" +
                                 "Build-Jdk: " + extractJdkVersion(getModule("project")) + "\n" +
                                 "Built-By: " + System.getProperty("user.name") + "\n" +
                                 "Created-By: " + ApplicationNamesInfo.getInstance().getFullProductName());
  }

  public void testDefaultEntries() throws Exception {
    importProject("<groupId>test</groupId>" +
                  "<artifactId>project</artifactId>" +
                  "<version>1</version>" +

                  "<dependencies>" +
                  "  <dependency>" +
                  "    <groupId>test</groupId>" +
                  "    <artifactId>other-project</artifactId>" +
                  "    <version>1</version>" +
                  "  </dependency>" +
                  "</dependencies>" +

                  "<build>" +
                  "    <plugins>" +
                  "        <plugin>" +
                  "            <artifactId>maven-jar-plugin</artifactId>" +
                  "            <version>2.4</version>" +
                  "            <configuration>" +
                  "                <archive>" +
                  "                    <manifest>" +
                  "                        <addDefaultImplementationEntries>true</addDefaultImplementationEntries>" +
                  "                        <addDefaultSpecificationEntries>true</addDefaultSpecificationEntries>" +
                  "                    </manifest>" +
                  "                </archive>" +
                  "            </configuration>" +
                  "        </plugin>" +
                  "    </plugins>" +
                  "</build>");

    compileModules("project");

    assertUnorderedLinesWithFile(getProjectPath() + "/target/MANIFEST.MF",
                                 "Manifest-Version: 1.0\n" +
                                 "Implementation-Version: 1\n" +
                                 "Implementation-Vendor-Id: test\n" +
                                 "Build-Jdk: " + extractJdkVersion(getModule("project")) + "\n" +
                                 "Built-By: " + System.getProperty("user.name") + "\n" +
                                 "Created-By: " + ApplicationNamesInfo.getInstance().getFullProductName() + "\n" +
                                 "Specification-Version: 1");
  }


  public void testManifestEntries() throws Exception {
    importProject("<groupId>test</groupId>" +
                  "<artifactId>project</artifactId>" +
                  "<version>1</version>" +

                  "<build>" +
                  "    <plugins>" +
                  "        <plugin>" +
                  "            <artifactId>maven-jar-plugin</artifactId>" +
                  "            <version>2.4</version>" +
                  "            <configuration>" +
                  "                <archive>" +
                  "                    <manifestEntries>" +
                  "                        <Dependencies>some.package</Dependencies>" +
                  "                        <otherEntry>other entry value </otherEntry>" +
                  "                    </manifestEntries>" +
                  "                </archive>" +
                  "            </configuration>" +
                  "        </plugin>" +
                  "    </plugins>" +
                  "</build>");

    compileModules("project");

    assertUnorderedLinesWithFile(getProjectPath() + "/target/MANIFEST.MF",
                                 "Manifest-Version: 1.0\n" +
                                 "otherEntry: other entry value\n" +
                                 "Dependencies: some.package\n" +
                                 "Build-Jdk: " + extractJdkVersion(getModule("project")) + "\n" +
                                 "Built-By: " + System.getProperty("user.name") + "\n" +
                                 "Created-By: " + ApplicationNamesInfo.getInstance().getFullProductName());
  }

  public void testManifestSections() throws Exception {
    importProject("<groupId>test</groupId>" +
                  "<artifactId>project</artifactId>" +
                  "<version>1</version>" +

                  "<build>" +
                  "    <plugins>" +
                  "        <plugin>" +
                  "            <artifactId>maven-jar-plugin</artifactId>" +
                  "            <version>2.4</version>" +
                  "            <configuration>" +
                  "                <archive>" +
                  "                    <manifestSections>" +
                  "                        <manifestSection>" +
                  "                            <name>org/test/Some.class</name>" +
                  "                            <manifestEntries>" +
                  "                                <Java-Bean>true</Java-Bean>" +
                  "                            </manifestEntries>" +
                  "                        </manifestSection>" +
                  "                        <manifestSection>" +
                  "                            <name>org/test/SomeOther.class</name>" +
                  "                            <manifestEntries>" +
                  "                                <Java-Bean>true</Java-Bean>" +
                  "                            </manifestEntries>" +
                  "                        </manifestSection>" +
                  "                    </manifestSections>" +
                  "                </archive>" +
                  "            </configuration>" +
                  "        </plugin>" +
                  "    </plugins>" +
                  "</build>");

    compileModules("project");

    assertUnorderedLinesWithFile(getProjectPath() + "/target/MANIFEST.MF",
                                 "Manifest-Version: 1.0\n" +
                                 "Build-Jdk: " + extractJdkVersion(getModule("project")) + "\n" +
                                 "Built-By: " + System.getProperty("user.name") + "\n" +
                                 "Created-By: " + ApplicationNamesInfo.getInstance().getFullProductName() + "\n" +
                                 "\n" +
                                 "Name: org/test/SomeOther.class\n" +
                                 "Java-Bean: true\n" +
                                 "\n" +
                                 "Name: org/test/Some.class\n" +
                                 "Java-Bean: true");
  }

  @Nullable
  public static String extractJdkVersion(@NotNull Module module) {
    String jdkVersion = null;
    Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    if (sdk != null && (jdkVersion = sdk.getVersionString()) != null) {
      final int quoteIndex = jdkVersion.indexOf('"');
      if (quoteIndex != -1) {
        jdkVersion = jdkVersion.substring(quoteIndex + 1, jdkVersion.length() - 1);
      }
    }

    return jdkVersion;
  }

  public static void assertUnorderedLinesWithFile(String filePath, String actualText) {
    String fileText;
    try {
      if (OVERWRITE_TESTDATA) {
        VfsTestUtil.overwriteTestData(filePath, actualText);
        System.out.println("File " + filePath + " created.");
      }
      fileText = FileUtil.loadFile(new File(filePath), CharsetToolkit.UTF8_CHARSET);
    }
    catch (FileNotFoundException e) {
      VfsTestUtil.overwriteTestData(filePath, actualText);
      throw new AssertionFailedError("No output text found. File " + filePath + " created.");
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    String expected = StringUtil.convertLineSeparators(fileText.trim());
    String actual = StringUtil.convertLineSeparators(actualText.trim());

    assertUnorderedElementsAreEqual(expected.split("\n"), actual.split("\n"));
  }
}
