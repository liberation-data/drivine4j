package org.drivine.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Data representation for anonymous web users.
 * Extends WebUserData to add the Anonymous label distinction.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnonymousWebUserData extends WebUserData {

    // No-arg constructor for Jackson
    public AnonymousWebUserData() {
        super();
    }

    public AnonymousWebUserData(String userId, String userDisplayName, String userUsername,
                                String userEmail, String passwordHash, String refreshToken) {
        super(userId, userDisplayName, userUsername, userEmail, passwordHash, refreshToken);
    }

    @Override
    public String toString() {
        return "AnonymousWebUserData{" +
            "userId='" + getId() + '\'' +
            ", userDisplayName='" + getDisplayName() + '\'' +
            ", userUsername='" + getUserName() + '\'' +
            '}';
    }
}
