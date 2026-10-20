package com.tokenx.auth.dto;

import com.tokenx.auth.model.AppUser;

/**
 * Public projection of a user. Contains only fields that are safe to expose: the password hash and
 * the account state never leave the server.
 *
 * @param id internal user id
 * @param username stored username (case preserved)
 * @param role {@code USER} or {@code ADMIN}
 */
public record UserResponse(Long id, String username, String role) {

  public static UserResponse from(AppUser user) {
    return new UserResponse(user.getId(), user.getUsername(), user.getRole().name());
  }
}
