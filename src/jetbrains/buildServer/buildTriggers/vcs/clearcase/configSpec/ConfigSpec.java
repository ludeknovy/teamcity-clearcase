/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec;

import java.io.IOException;
import java.util.List;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.CCPathElement;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseConnection;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.VersionTree;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

public interface ConfigSpec {
  @Nullable
  Version getCurrentVersion(final String ccViewRoot, final String fullFileName, final VersionTree versionTree, final boolean isFile) throws IOException, VcsException;

  boolean isVersionIsInsideView(final ClearCaseConnection elements, final List<CCPathElement> pathElements, final boolean isFile) throws VcsException, IOException;

  @NotNull
  List<ConfigSpecLoadRule> getLoadRules();

  boolean isUnderLoadRules(final String ccViewRoot, final String fullFileName) throws IOException, VcsException;

  void setViewIsDynamic(final boolean viewIsDynamic);
}
