/*
  Copyright 2013 - 2014 Jerome Leleu

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.pac4j.j2e.filter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.pac4j.core.client.Client;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.RequiresHttpAction;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.j2e.configuration.ClientsConfiguration;
import org.pac4j.j2e.util.UserUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This filter aims to protect a secured resource.<br>
 * It handles both statefull (default) or stateless resources by delegating to a pac4j client.<br>
 *  - If statefull, it relies on the session and on the callback filter to terminate the authentication process.<br>
 *  - If stateless it validates the provided credentials and forward the request to
 * the underlying resource if the authentication succeeds.<br>
 * The filter also handles basic authorization based on two parameters: requireAnyRole and requireAllRoles.
 * 
 * @author Jerome Leleu, Michael Remond
 * @since 1.0.0
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class RequiresAuthenticationFilter extends ClientsConfigFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequiresAuthenticationFilter.class);

    private String clientName;

    private boolean stateless = false;

    private boolean isAjax = false;

    private String requireAnyRole;

    private String requireAllRoles;

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);
        clientName = filterConfig.getInitParameter("clientName");
        String statelessParam = filterConfig.getInitParameter("stateless");
        if (statelessParam != null) {
            stateless = Boolean.parseBoolean(statelessParam);
        }
        String isAjaxParam = filterConfig.getInitParameter("isAjax");
        if (isAjaxParam != null) {
            isAjax = Boolean.parseBoolean(isAjaxParam);
        }
        requireAnyRole = filterConfig.getInitParameter("requireAnyRole");
        requireAllRoles = filterConfig.getInitParameter("requireAllRoles");
    }

    @Override
    protected final void internalFilter(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain chain) throws IOException, ServletException {

        final WebContext context = new J2EContext(request, response);

        // Try to get identity
        CommonProfile profile = null;
        try {
            profile = retrieveUserProfile(request, response, context);
        } catch (RequiresHttpAction e) {
            logger.debug("extra HTTP action required : {}", e.getCode());
            return;
        }

        // authentication success or failure strategy
        if (profile == null) {
            authenticationFailure(request, response, chain, context);
        } else {
            saveUserProfile(profile, request);
            authenticationSuccess(profile, request, response, chain, context);
        }

    }

    /**
     * Retrieve user profile either by looking in the session or trying to authenticate directly
     * if stateless web service.
     * 
     * @param request
     * @param response
     * @param context
     * @return
     * @throws RequiresHttpAction
     */
    protected CommonProfile retrieveUserProfile(HttpServletRequest request, HttpServletResponse response,
            WebContext context) throws RequiresHttpAction {
        if (isStateless()) {
            return authenticate(request, response, context);
        } else {
            CommonProfile profile = UserUtils.getProfile(request);
            logger.debug("profile : {}", profile);
            return profile;
        }
    }

    /**
     * Save the user profile in session or attach it to the request if stateless web service.
     * 
     * @param profile
     * @param request
     */
    protected void saveUserProfile(CommonProfile profile, HttpServletRequest request) {
        UserUtils.setProfile(request, profile, isStateless());
    }

    /**
     * Default authentication success strategy which forward to the next filter if the user
     * has access or returns an access denied error otherwise.
     * 
     * @param profile
     * @param request
     * @param response
     * @param chain
     * @param context 
     * @throws IOException
     * @throws ServletException
     */
    protected void authenticationSuccess(CommonProfile profile, HttpServletRequest request,
            HttpServletResponse response, FilterChain chain, WebContext context) throws IOException, ServletException {

        if (hasAccess(profile, request)) {
            chain.doFilter(request, response);
        } else {
            context.setResponseStatus(HttpConstants.FORBIDDEN);
        }
    }

    /**
     * Returns true if the user defined by the profile has access to the underlying resource
     * depending on the requireAnyRole and requireAllRoles fields.
     * 
     * @param profile
     * @param request 
     * @return
     */
    protected boolean hasAccess(CommonProfile profile, HttpServletRequest request) {
        return profile.hasAccess(requireAnyRole, requireAllRoles);
    }

    /**
     * Default authentication failure strategy which generates an unauthorized page if stateless web service
     * or redirect to the authentication provider after saving the original url.
     * 
     * @param request
     * @param response
     * @param chain 
     * @param context
     * @throws IOException
     * @throws ServletException
     */
    protected void authenticationFailure(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            WebContext context) throws IOException, ServletException {

        if (isStateless()) {
            context.setResponseStatus(HttpConstants.UNAUTHORIZED);
        } else {
            // no authentication tried -> redirect to provider
            // keep the current url
            saveOriginalUrl(request);
            // compute and perform the redirection
            redirectToIdentityProvider(request, context);
        }
    }

    /**
     * Authenticates the current request by getting the credentials and the corresponding user profile.
     * 
     * @param request
     * @param response
     * @param context
     * @return
     * @throws RequiresHttpAction
     */
    protected CommonProfile authenticate(HttpServletRequest request, HttpServletResponse response, WebContext context)
            throws RequiresHttpAction {
        String currentClientName = getClientName(context);

        final Client client = ClientsConfiguration.getClients().findClient(currentClientName);
        logger.debug("client : {}", client);

        final Credentials credentials;
        credentials = client.getCredentials(context);
        logger.debug("credentials : {}", credentials);

        // get user profile
        CommonProfile profile = (CommonProfile) client.getUserProfile(credentials, context);
        logger.debug("profile : {}", profile);

        return profile;
    }

    protected void saveOriginalUrl(HttpServletRequest request) {
        if (!isAjaxRequest(request)) {
            String requestedUrl = request.getRequestURL().toString();
            String queryString = request.getQueryString();
            if (CommonHelper.isNotBlank(queryString)) {
                requestedUrl += "?" + queryString;
            }
            logger.debug("requestedUrl : {}", requestedUrl);
            request.getSession(true).setAttribute(HttpConstants.REQUESTED_URL, requestedUrl);
        }
    }

    protected String retrieveOriginalUrl(HttpServletRequest request) {
        return (String) request.getSession(true).getAttribute(HttpConstants.REQUESTED_URL);
    }

    protected boolean isAjaxRequest(HttpServletRequest request) {
        return isAjax;
    }

    private void redirectToIdentityProvider(HttpServletRequest request, WebContext context) {
        Client<Credentials, CommonProfile> client = ClientsConfiguration.getClients()
                .findClient(getClientName(context));
        try {
            client.redirect(context, true, isAjaxRequest(request));
        } catch (RequiresHttpAction e) {
            logger.debug("extra HTTP action required : {}", e.getCode());
        }
    }

    /**
     * Indicates wether this authentication filter protects a stateless web service or not.
     * 
     * @return
     */
    private boolean isStateless() {
        return stateless;
    }

    /**
     * Get the client name from the context (GET parameter) or from the configuration. 
     * The configuration client name overrides the one from the context. 
     * 
     * @param context
     * @return
     */
    private String getClientName(WebContext context) {
        return (clientName != null) ? clientName : context.getRequestParameter(ClientsConfiguration.getClients()
                .getClientNameParameter());
    }

}
