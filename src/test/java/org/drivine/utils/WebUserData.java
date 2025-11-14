package org.drivine.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Base class representing web user data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebUserData {
    private String userId;
    private String userDisplayName;
    private String userUsername;
    private String userEmail;
    private String passwordHash;
    private String refreshToken;

    // No-arg constructor
    public WebUserData() {
    }

    public WebUserData(String userId, String userDisplayName, String userUsername,
                       String userEmail, String passwordHash, String refreshToken) {
        this.userId = userId;
        this.userDisplayName = userDisplayName;
        this.userUsername = userUsername;
        this.userEmail = userEmail;
        this.passwordHash = passwordHash;
        this.refreshToken = refreshToken;
    }

    public String getId() {
        return userId;
    }

    public void setId(String userId) {
        this.userId = userId;
    }

    public String getDisplayName() {
        return userDisplayName;
    }

    public void setDisplayName(String userDisplayName) {
        this.userDisplayName = userDisplayName;
    }

    public String getUserName() {
        return userUsername;
    }

    public void setUserName(String userUsername) {
        this.userUsername = userUsername;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
