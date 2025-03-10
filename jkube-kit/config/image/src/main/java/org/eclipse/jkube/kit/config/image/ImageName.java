/*
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.config.image;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for parsing docker repository/image names:
 *
 * <ul>
 *     <li>If the first part before the slash contains a "." or a ":" it is considered to be a registry URL</li>
 *     <li>A last part starting with a ":" is considered to be a tag</li>
 *     <li>The rest is considered the repository name (which might be separated via slashes)</li>
 * </ul>
 *
 * Example of valid names:
 *
 * <ul>
 *     <li>consol/tomcat-8.0</li>
 *     <li>consol/tomcat-8.0:8.0.9</li>
 *     <li>docker.consol.de:5000/tomcat-8.0</li>
 *     <li>docker.consol.de:5000/jolokia/tomcat-8.0:8.0.9</li>
 * </ul>
 *
 * @author roland
 * @since 22.07.14
 */
public class ImageName {

    // The repository part of the full image
    private String repository;

    // Registry
    private String registry;

    // Tag name
    private String tag;

    // Digest
    private String digest;

    private static final int REPO_NAME_MAX_LENGTH = 255;

    /**
     * Create an image name
     *
     * @param fullName The fullname of the image in Docker format.
     */
    public ImageName(String fullName) {
        this(fullName,null);
    }

    /**
     * Create an image name with a tag. If a tag is provided (i.e. is not null) then this tag is used.
     * Otherwise the tag of the provided name is used (if any).
     *
     * @param fullName The fullname of the image in Docker format. I
     * @param givenTag tag to use. Can be null in which case the tag specified in fullName is used.
     */
    public ImageName(String fullName, String givenTag) {
        if (fullName == null) {
            throw new NullPointerException("Image name must not be null");
        }

        // set digest to null as default
        digest = null;
        // check if digest is part of fullName, if so -> extract it
        if(fullName.contains("@sha256")) { // Of it contains digest
            String[] digestParts = fullName.split("@");
            digest = digestParts[1];
            fullName = digestParts[0];
        }

        // check for tag
        Pattern tagPattern = Pattern.compile("^(.+?)(?::([^:/]+))?$");
        Matcher matcher = tagPattern.matcher(fullName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(fullName + " is not a proper image name ([registry/][repo][:port]");
        }
        // extract tag if it exists
        tag = givenTag != null ? givenTag : matcher.group(2);
        String rest = matcher.group(1);

        // extract registry, repository, user
        parseComponentsBeforeTag(rest);

        /*
         * set tag to latest if tag AND digest are null
         * if digest is not null but tag is -> leave it!
         *  -> in case of "image_name@sha256" it is not required to get resolved to "latest"
         */
        if (tag == null && digest == null) {
            tag = "latest";
        }

        doValidate();
    }

    public String getRepository() {
        return repository;
    }

    public String getRegistry() {
        return registry;
    }

    public String getTag() {
        return tag;
    }

    public String getDigest() {
        return digest;
    }

    public boolean hasRegistry() {
        return registry != null && registry.length() > 0;
    }

    public boolean isFullyQualifiedName() {
        if (StringUtils.isNotBlank(registry) && containsColon(registry)) {
            return true;
        }
        return StringUtils.isNotBlank(registry) &&
            StringUtils.isNotBlank(inferUser()) &&
            StringUtils.isNotBlank(getRepository()) &&
            (StringUtils.isNotBlank(getTag()) || StringUtils.isNotBlank(getDigest()));
    }

    private String joinTail(String[] parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 1;i < parts.length; i++) {
            builder.append(parts[i]);
            if (i < parts.length - 1) {
                builder.append("/");
            }
        }
        return builder.toString();
    }

    private boolean containsPeriodOrColon(String part) {
        return containsPeriod(part) || containsColon(part);
    }

    private boolean containsPeriod(String part) {
        return part.contains(".");
    }

    private boolean containsColon(String part) {
        return part.contains(":");
    }

    /**
     * Get the full name of this image, including the registry but without
     * any tag (e.g. <code>privateregistry:fabric8io/java</code>)
     *
     * @return full name with the original registry
     */
    public String getNameWithoutTag() {
        return getNameWithoutTag(null);
    }

    /**
     * Get the full name of this image like {@link #getNameWithoutTag()} does, but allow
     * an optional registry. This registry is used when this image does not already
     * contain a registry.
     *
     * @param optionalRegistry optional registry to use when this image does not provide
     *                         a registry. Can be null in which case no optional registry is used*
     * @return full name with original registry (if set) or optional registry (if not <code>null</code>)
     */
    public String getNameWithoutTag(String optionalRegistry) {
        StringBuilder ret = new StringBuilder();
        if (!isFullyQualifiedName() && isRegistryValidPathComponent() &&
            StringUtils.isNotBlank(optionalRegistry) && !optionalRegistry.equals(registry)) {
            ret.append(optionalRegistry).append("/").append(registry).append("/");
        } else if (registry != null || optionalRegistry != null) {
            ret.append(registry != null ? registry : optionalRegistry).append("/");
        }
        ret.append(repository);
        return ret.toString();
    }


    /**
     * Get the full name of this image, including the registry and tag
     * (e.g. <code>privateregistry:fabric8io/java:7u53</code>)
     *
     * @return full name with the original registry and the original tag given (if any).
     */
    public String getFullName() {
        return getFullName(null);
    }

    /**
     * Get the full name of this image like {@link #getFullName()} does, but allow
     * an optional registry. This registry is used when this image does not already
     * contain a registry. If no tag was provided in the initial name, <code>latest</code> is used.
     *
     * @param optionalRegistry optional registry to use when this image does not provide
     *                         a registry. Can be null in which case no optional registry is used*
     * @return full name with original registry (if set) or optional registry (if not <code>null</code>).
     */
    public String getFullName(String optionalRegistry) {
        String fullName = getNameWithoutTag(optionalRegistry);
        if (tag != null) {
            fullName = fullName +  ":" + tag;
        }
        if(digest != null) {
            fullName = fullName + "@" + digest;
        }
        return fullName;
    }

    /**
     * Try to infer the user (or "project") part of the image name. This is usually the part after the registry
     * and before the image name.
     * <p> The main purpose of this method is to allow the inference of user credentials for the registry from the image
     * name.
     *
     * @return user part or <code>null</code> if no user can't be inferred from the image name.
     */
    public String inferUser() {
        if (StringUtils.isNotBlank(repository)) {
            if (repository.contains("/")) {
                return repository.split("/")[0];
            }
            return null;
        }
        return null;
    }

    /**
     * Get the simple name of the image, which is the repository sans the user parts.
     *
     * @return simple name of the image
     */
    public String getSimpleName() {
        int delimiterIndex = repository.indexOf('/');
        if (delimiterIndex >= 0) {
            return repository.substring(delimiterIndex + 1);
        }
        return repository;
    }

    /**
     * Check whether the given name validates against the Docker rules for names
     *
     * @param image image name to validate
     */
    public static void validate(String image) {
        // Validation will be triggered during construction
        new ImageName(image);
    }

    // Validate parts and throw an IllegalArgumentException if a part is not valid
    private void doValidate() {
        List<String> errors = new ArrayList<>();
        // Strip off user from repository name
        String user = inferUser();
        String image = user != null ? repository.substring(user.length() + 1) : repository;
        Object[] checks = new Object[] {
                "registry", DOMAIN_REGEXP, registry,
                "image", IMAGE_NAME_REGEXP, image,
                "user", NAME_COMP_REGEXP, user,
                "tag", TAG_REGEXP, tag,
                "digest", DIGEST_REGEXP, digest
        };

        if (repository.length() > REPO_NAME_MAX_LENGTH) {
            errors.add(String.format("Repository name must not be more than %d characters", REPO_NAME_MAX_LENGTH));
        }

        for (int i = 0; i < checks.length; i +=3) {
            String value = (String) checks[i + 2];
            Pattern checkPattern = (Pattern) checks[i + 1];
            if (value != null &&
                    !checkPattern.matcher(value).matches()) {
                errors.add(String.format("%s part '%s' doesn't match allowed pattern '%s'",
                        checks[i], value, checkPattern.pattern()));
            }
        }
        if (!errors.isEmpty()) {
            StringBuilder buf = new StringBuilder();
            buf.append(String.format("Given Docker name '%s' is invalid:%n", getFullName()));
            for (String error : errors) {
                buf.append(String.format("   * %s%n",error));
            }
            buf.append("See http://bit.ly/docker_image_fmt for more details");
            throw new IllegalArgumentException(buf.toString());
        }
    }

    private void parseComponentsBeforeTag(String rest) {
        String[] parts = rest.split("\\s*/\\s*");
        if (parts.length == 1) {
            registry = null;
            repository = parts[0];
        } else if (parts.length >= 2) {
            if (isValidDomain(parts[0])) {
                registry = parts[0];
                repository = joinTail(parts);
            } else {
                registry = null;
                repository = rest;
            }
        }
    }

    private boolean isValidDomain(String str) {
        return containsPeriodOrColon(str) && DOMAIN_REGEXP.matcher(str).matches();
    }

    private boolean isRegistryValidPathComponent() {
        return StringUtils.isNotBlank(registry) && !containsColon(registry);
    }

    // ================================================================================================

    // Validations patterns, taken directly from the docker source -->
    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go
    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/reference.go

    // ---------------------------------------------------------------------
    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L18
    private static final String NAME_COMPONENT_REGEXP = "[a-z0-9]+(?:(?:(?:[._]|__|[-]*)[a-z0-9]+)+)?";

    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L25
    private static final String DOMAIN_COMPONENT_REGEXP = "(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])";

    // ==========================================================

    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L18
    private static final Pattern NAME_COMP_REGEXP = Pattern.compile(NAME_COMPONENT_REGEXP);

    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L53
    private static final Pattern IMAGE_NAME_REGEXP = Pattern.compile(NAME_COMPONENT_REGEXP + "(?:(?:/" + NAME_COMPONENT_REGEXP + ")+)?");

    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L31
    private static final Pattern DOMAIN_REGEXP = Pattern.compile("^" + DOMAIN_COMPONENT_REGEXP + "(?:\\." + DOMAIN_COMPONENT_REGEXP + ")*(?::[0-9]+)?$");

    // https://github.com/docker/docker/blob/04da4041757370fb6f85510c8977c5a18ddae380/vendor/github.com/docker/distribution/reference/regexp.go#L37
    private static final Pattern TAG_REGEXP = Pattern.compile("^[\\w][\\w.-]{0,127}$");

    private static final Pattern DIGEST_REGEXP = Pattern.compile("^sha256:[a-z0-9]{32,}$");
}
