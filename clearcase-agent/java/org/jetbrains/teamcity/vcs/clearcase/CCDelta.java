package org.jetbrains.teamcity.vcs.clearcase;


public class CCDelta {
  
  public enum Kind {
    ADDITION, MODIFICATION, DELETION
  }

  private CCSnapshotView myView;
  
  private Kind myKind;
  
  private String myPath;

  private String myRevBefor;

  private String myRevAfter;  
  
  
  CCDelta(CCSnapshotView view, boolean isAddition, boolean isChange, boolean isDeletion, String path, String revBefor, String revAfter){
    myView = view;
    //kind
    if(isAddition){
      myKind = Kind.ADDITION;
    } else if (isDeletion){
      myKind = Kind.DELETION;
    } else {
      myKind = Kind.MODIFICATION;
    }
    //resource
    myPath = path;
    //revisions 
    myRevBefor = revBefor;
    myRevAfter = revAfter;
    
  }


  /**
   * 
   * @return View the change belongs to 
   */
  public CCSnapshotView getView() {
    return myView;
  }


  public Kind getKind() {
    return myKind;
  }

  /**
   * 
   * @return A file within the View
   */
  public String getPath() {
    return myPath;
  }


  public String getRevisionBefor() {
    return myRevBefor;
  }


  public String getRevisionAfter() {
    return myRevAfter;
  }
  
  @Override
  public String toString() {
    return String.format("{CCChange: kind=%s, path=\"%s\", befor=%s, after=%s}", getKind(), getPath(), getRevisionBefor(), getRevisionAfter());
  }
  
  
}
