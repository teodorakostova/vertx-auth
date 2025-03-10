/*
 * Copyright 2014 Red Hat, Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.auth.mongo.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.HashingStrategy;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import io.vertx.ext.auth.mongo.*;
import io.vertx.ext.mongo.MongoClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An implementation of {@link MongoAuthentication}
 *
 * @author mremme
 */
public class MongoAuthenticationImpl implements MongoAuthentication {

  private static final Logger log = LoggerFactory.getLogger(MongoAuthenticationImpl.class);

  private final HashingStrategy strategy = HashingStrategy.load();
  private final MongoClient mongoClient;
  private final MongoAuthenticationOptions options;
  private HashStrategy legacyStrategy;
  private String hashField;

  /**
   * Creates a new instance
   *
   * @param mongoClient the {@link MongoClient} to be used
   * @param options     the options for configuring the new instance
   */
  public MongoAuthenticationImpl(MongoClient mongoClient, MongoAuthenticationOptions options) {
    this.mongoClient = mongoClient;
    this.options = options;
  }

  /**
   * Provided for backward compatibility
   *
   * @param mongoClient
   * @param legacyStrategy
   * @param options
   */
  public MongoAuthenticationImpl(MongoClient mongoClient, HashStrategy legacyStrategy, String hashField, MongoAuthenticationOptions options) {
    this.mongoClient = mongoClient;
    this.options = options;
    this.legacyStrategy = legacyStrategy;
    this.hashField = hashField;
  }

  @Override
  public void authenticate(JsonObject credentials, Handler<AsyncResult<User>> resultHandler) {
    authenticate(credentials)
      .onComplete(resultHandler);
  }

  @Override
  public Future<User> authenticate(JsonObject credentials) {
    return authenticate(
      new UsernamePasswordCredentials(
        credentials.getString(options.getUsernameCredentialField()),
        credentials.getString(options.getPasswordCredentialField())));
  }

  @Override
  public Future<User> authenticate(Credentials credentials) {
    // Null username is invalid
    if (credentials == null) {
      return Future.failedFuture("Credentials must be set for authentication.");
    }

    final UsernamePasswordCredentials authInfo;

    try {
      authInfo = (UsernamePasswordCredentials) credentials;
      authInfo.checkValid(null);
    } catch (RuntimeException e) {
      return Future.failedFuture(e);
    }

    AuthToken token = new AuthToken(authInfo.getUsername(), authInfo.getPassword());

    JsonObject query = createQuery(authInfo.getUsername());
    return mongoClient
      .find(options.getCollectionName(), query)
      .compose(rows -> {
        try {
          User user = handleSelection(rows, token);
          return Future.succeededFuture(user);
        } catch (Exception e) {
          return Future.failedFuture(e);
        }
      });
  }

  /**
   * The default implementation uses the usernameField as search field
   *
   * @param username
   * @return
   */
  protected JsonObject createQuery(String username) {
    return new JsonObject().put(options.getUsernameField(), username);
  }

  /**
   * Examine the selection of found users and return one, if password is fitting,
   *
   * @param resultList
   * @param authToken
   * @return
   */
  private User handleSelection(List<JsonObject> resultList, AuthToken authToken) throws Exception {
    switch (resultList.size()) {
      case 0: {
        String message = "No account found for user [" + authToken.username + "]";
        // log.warn(message);
        throw new Exception(message);
      }
      case 1: {
        JsonObject json = resultList.get(0);
        User user = createUser(json);
        if (examinePassword(user, json.getString(options.getPasswordField()), authToken.password))
          return user;
        else {
          String message = "Invalid username/password [" + authToken.username + "]";
          // log.warn(message);
          throw new Exception(message);
        }
      }
      default: {
        // More than one row returned!
        String message = "More than one user row found for user [" + authToken.username + "( " + resultList.size() + " )]. Usernames must be unique.";
        // log.warn(message);
        throw new Exception(message);
      }
    }
  }

  private User createUser(JsonObject json) {
    User user = User.create(json);
    // metadata "amr"
    user.principal().put("amr", Collections.singletonList("pwd"));

    if (legacyStrategy != null) {
      json.put(MongoAuthImpl.PROPERTY_FIELD_SALT, hashField);
      json.put(MongoAuthImpl.PROPERTY_FIELD_PASSWORD, options.getPasswordField());
    }
    return user;
  }

  private boolean examinePassword(User user, String hash, String password) {

    if (hash.charAt(0) != '$') {
      // this isn't a phc-string, it's legacy
      if (legacyStrategy == null) {
        throw new IllegalStateException("Mongo Authentication cannot handle legacy hashes without a HashStrategy");
      }

      String givenPassword = this.legacyStrategy.computeHash(password, user);
      return MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), givenPassword.getBytes(StandardCharsets.UTF_8));
    } else {
      return strategy.verify(hash, password);
    }
  }

  @Override
  public String hash(String id, Map<String, String> params, String salt, String password) {
    return strategy.hash(id, params, salt, password);
  }


  /**
   * The incoming data from an authentication request
   *
   * @author mremme
   */
  static class AuthToken {
    final String username;
    final String password;

    AuthToken(String username, String password) {
      this.username = username;
      this.password = password;
    }
  }
}
