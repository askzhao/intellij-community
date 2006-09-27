package com.intellij.openapi.projectRoots.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.ExportableApplicationComponent;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.NamedJDOMExternalizable;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.util.EventDispatcher;
import com.intellij.util.SystemProperties;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectJdkTableImpl extends ProjectJdkTable implements NamedJDOMExternalizable, ExportableApplicationComponent {
  private ArrayList<ProjectJdk> myJdks = new ArrayList<ProjectJdk>();
  private ProjectJdk myInternalJdk;
  private EventDispatcher<Listener> myEventDispatcher = EventDispatcher.create(Listener.class);
  private JavaSdk myJavaSdk;
  @NonNls private static final String ELEMENT_JDK = "jdk";

  private final Map<String, ProjectJdkImpl> myCachedProjectJdks = new HashMap<String, ProjectJdkImpl>();

  public ProjectJdkTableImpl(JavaSdk javaSdk) {
    myJavaSdk = javaSdk;
  }

  public void disposeComponent() {
  }

  public void initComponent() { }

  @NotNull
  public File[] getExportFiles() {
    return new File[]{PathManager.getOptionsFile(this)};
  }

  @NotNull
  public String getPresentableName() {
    return ProjectBundle.message("sdk.table.settings");
  }

  @Nullable
  public ProjectJdk findJdk(String name) {
    for (ProjectJdk jdk : myJdks) {
      if (Comparing.strEqual(name, jdk.getName())) {
        return jdk;
      }
    }
    return null;
  }

  @Nullable
  public ProjectJdk findJdk(String name, String type) {
    final String sdkTypeName = type != null ? type : JavaSdk.getInstance().getName();
    ProjectJdk projectJdk = findJdk(name);
    if (projectJdk != null){
      return projectJdk;
    }

    final String uniqueName = sdkTypeName + "." + name;
    projectJdk = myCachedProjectJdks.get(uniqueName);
    if (projectJdk != null) return projectJdk;

    @NonNls final String jdkPrefix = "jdk.";
    final String jdkPath = System.getProperty(jdkPrefix + name);
    if (jdkPath == null) return null;

    final SdkType[] sdkTypes = ApplicationManager.getApplication().getComponents(SdkType.class);
    for (SdkType sdkType : sdkTypes) {
      if (Comparing.strEqual(sdkTypeName, sdkType.getName())){
        if (sdkType.isValidSdkHome(jdkPath)) {
          ProjectJdkImpl projectJdkImpl = new ProjectJdkImpl(name, sdkType);
          projectJdkImpl.setHomePath(jdkPath);
          sdkType.setupSdkPaths(projectJdkImpl);
          myCachedProjectJdks.put(uniqueName, projectJdkImpl);
          return projectJdkImpl;
        }
        break;
      }
    }
    return null;
  }

  public ProjectJdk getInternalJdk() {
    if (myInternalJdk == null) {
      final String jdkHome = SystemProperties.getJavaHome();
      final String versionName = ProjectBundle.message("sdk.java.name.template", SystemProperties.getJavaVersion());
      myInternalJdk = myJavaSdk.createJdk(versionName, jdkHome);
    }
    return myInternalJdk;
  }

  public int getJdkCount() {
    return myJdks.size();
  }

  public ProjectJdk[] getAllJdks() {
    return myJdks.toArray(new ProjectJdk[myJdks.size()]);
  }

  public void addJdk(ProjectJdk jdk) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    myJdks.add(jdk);
    myEventDispatcher.getMulticaster().jdkAdded(jdk);
  }

  public void removeJdk(ProjectJdk jdk) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    myEventDispatcher.getMulticaster().jdkRemoved(jdk);
    myJdks.remove(jdk);
    if (jdk.equals(myInternalJdk)) {
      myInternalJdk = null;
    }
  }

  public void updateJdk(ProjectJdk originalJdk, ProjectJdk modifiedJdk) {
    final String previousName = originalJdk.getName();
    final String newName = modifiedJdk.getName();

    ((ProjectJdkImpl)modifiedJdk).copyTo((ProjectJdkImpl)originalJdk);

    if (previousName != null ? !previousName.equals(newName) : newName != null) {
      // fire changes because after renaming JDK its name may match the associated jdk name of modules/project
      myEventDispatcher.getMulticaster().jdkNameChanged(originalJdk, previousName);
    }
  }

  public void addListener(ProjectJdkTable.Listener listener) {
    myEventDispatcher.addListener(listener);
  }

  public void removeListener(ProjectJdkTable.Listener listener) {
    myEventDispatcher.removeListener(listener);
  }

  public void readExternal(Element element) throws InvalidDataException {
    myInternalJdk = null;
    myJdks.clear();

    final List children = element.getChildren(ELEMENT_JDK);
    final List<ProjectJdkImpl> jdks = new ArrayList<ProjectJdkImpl>(children.size());
    try {
      for (final Object aChildren : children) {
        final Element e = (Element)aChildren;
        final ProjectJdkImpl jdk = new ProjectJdkImpl(null, null);
        jdk.readExternal(e);
        jdks.add(jdk);
      }
    }
    finally {
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        public void run() {
          for (final ProjectJdkImpl jdk : jdks) {
            addJdk(jdk);
          }
        }
      });
      getInternalJdk();
    }
  }

  public void writeExternal(Element element) throws WriteExternalException {
    for (ProjectJdk jdk : myJdks) {
      final Element e = new Element(ELEMENT_JDK);
      element.addContent(e);
      ((ProjectJdkImpl)jdk).writeExternal(e);
    }
  }

  public String getExternalFileName() {
    return "jdk.table";
  }

  @NotNull
  public String getComponentName() {
    return "ProjectJdkTable";
  }

}