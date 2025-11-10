package org.drivine.utils;

public class JavaUser {
  private String username;
  private boolean active;
  private int[] scores = new int[] {1, 2, 3};

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }

  public int[] getScores() { return scores; }
  public void setScores(int[] scores) { this.scores = scores; }
}
