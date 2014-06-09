/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.unifiedpush.test;

import com.google.android.gcm.server.Message;
import com.google.android.gcm.server.MulticastResult;
import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;
import com.notnoop.apns.APNS;
import com.notnoop.apns.ApnsDelegate;
import com.notnoop.apns.ApnsDelegateAdapter;
import com.notnoop.apns.ApnsNotification;
import com.notnoop.apns.ApnsService;
import com.notnoop.apns.ApnsServiceBuilder;
import com.notnoop.apns.EnhancedApnsNotification;
import com.notnoop.apns.PayloadBuilder;
import com.notnoop.apns.internal.ApnsServiceImpl;
import com.notnoop.exceptions.NetworkIOException;
import org.arquillian.spacelift.execution.ExecutionException;
import org.arquillian.spacelift.execution.Tasks;
import org.arquillian.spacelift.process.impl.CommandTool;
import org.jboss.aerogear.test.api.sender.SenderStatistics;
import org.jboss.aerogear.unifiedpush.keycloak.BootstrapListener;
import org.jboss.aerogear.unifiedpush.message.sender.GCMForChromePushNotificationSender;
import org.jboss.aerogear.unifiedpush.utils.Constants;
import org.jboss.aerogear.unifiedpush.utils.SenderStatisticsEndpoint;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Filter;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.PomEquippedResolveStage;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepositories;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Deployments {

    private static final Logger LOGGER = Logger.getLogger(Deployments.class.getName());

    public static final String AG_PUSH = "ag_push";
    public static final String AUTH_SERVER = "auth_server";

    private static final String PROPERTY_UPS_SOURCE = "ups.source";
    private static final String PROPERTY_UPS_VERSION = "ups.version";
    private static final String PROPERTY_UPS_REMOTE_URL = "ups.remote.url";
    private static final String PROPERTY_UPS_LOCAL_POM = "ups.local.pom";

    private static final String UPS_SOURCE_REMOTE = "remote";
    private static final String UPS_SOURCE_LOCAL = "local";

    private static final String UPS_MINIMUM_VERSION = "[0.10.0,)";

    private static final String UPS_SOURCE_DEFAULT = UPS_SOURCE_LOCAL;
    private static final String UPS_REMOTE_URL_DEFAULT = "http://dl.bintray.com/aerogear/AeroGear-UnifiedPush/";
    private static final String UPS_LOCAL_POM_DEFAULT = "aerogear-unifiedpush-server/pom.xml";

    private static final AtomicBoolean mavenBuildInvoked = new AtomicBoolean(false);

    private Deployments() {
        throw new UnsupportedOperationException("No instantiation.");
    }

    /**
     * Gets WebArchive of Unified Push Server with replaced persistence.xml files. The source of the server can be
     * configured and defaults to release.
     */
    public static WebArchive unifiedPushServer() {
        String upsSource = getUpsSource();

        WebArchive war;
        if (upsSource.equalsIgnoreCase(UPS_SOURCE_REMOTE)) {
            war = remoteUnifiedPushServer();
        } else if (upsSource.equalsIgnoreCase(UPS_SOURCE_LOCAL)) {
            war = localUnfiedPushServer();
        } else {
            throw new IllegalArgumentException("Unsupported source of Unified Push Server WAR: " + upsSource + "!");
        }

        // FIXME only >= 0.11.0 will be supported
        // try to figure out whether UPS was modularized already
        String upsVersion = System.getProperty(PROPERTY_UPS_VERSION);
        if (upsVersion != null && upsVersion.length() != 0 && upsVersion.startsWith("0.10")) {
            replacePersistenceInWarPreModularization(war);
        } else {
            war.deleteClass(BootstrapListener.class);
            war.addClass(BootstrapListener.class);

            replacePersistenceInWar(war);
        }

        return war;
    }

    /**
     * Gets WebArchive of Unified Push Server with replaced persistence.xml files, custom sender libraries (GCM
     * and APNS) and bundled SenderStatisticsEndpoint for Message testing.
     *
     * @see Deployments#unifiedPushServer()
     */
    public static WebArchive unifiedPushServerWithCustomSenders() {
        WebArchive war = unifiedPushServer();

        Map<ArchivePath, Node> librariesToRemove = war.getContent(new Filter<ArchivePath>() {
            @Override
            public boolean include(ArchivePath path) {
                return (path.get().startsWith("/WEB-INF/lib/apns") ||
                        path.get().startsWith("/WEB-INF/lib/gcm-server")) && path.get().endsWith(".jar");

            }
        });

        for (ArchivePath archivePath : librariesToRemove.keySet()) {
            war.delete(archivePath);
        }

        war.addClasses(SenderStatisticsEndpoint.class, SenderStatistics.class,
                GCMForChromePushNotificationSender.class);

        JavaArchive gcmJar = ShrinkWrap.create(JavaArchive.class, "gcm-server.jar").addClasses(Result.class,
                Message.class, MulticastResult.class, Message.class, Sender.class);
        JavaArchive apnsJar = ShrinkWrap.create(JavaArchive.class, "apns.jar").addClasses(NetworkIOException.class,
                ApnsService.class, ApnsServiceImpl.class, ApnsServiceBuilder.class, PayloadBuilder.class, APNS.class,
                Constants.class, ApnsNotification.class, EnhancedApnsNotification.class, ApnsDelegateAdapter.class,
                ApnsDelegate.class
        );
        war.addAsLibraries(gcmJar, apnsJar);

        PomEquippedResolveStage resolver = Maven.resolver().loadPomFromFile("pom.xml");

        // here we resolve mockito transitively, other artifact without transitivity
        File[] libs = resolver.resolve("com.jayway.restassured:rest-assured", "com.jayway.awaitility:awaitility")
                .withoutTransitivity().asFile();
        war.addAsLibraries(libs);
        libs = resolver.resolve("org.mockito:mockito-core").withTransitivity().asFile();
        war = war.addAsLibraries(libs);

        return war;
    }

    /**
     * Removes original persistence.xml files from unfiedpush-model-jpa JAR and from the war and replaces them with
     * custom ones. This way we change what kind of storage is used.
     *
     * @param war WebArchive to be modified.
     */
    private static void replacePersistenceInWar(WebArchive war) {
        Collection<JavaArchive> jpaModels = war.getAsType(JavaArchive.class, new Filter<ArchivePath>() {
            @Override
            public boolean include(ArchivePath path) {
                return path.get().startsWith("/WEB-INF/lib/unifiedpush-model-jpa") && path.get().endsWith(".jar");
            }
        });

        for (JavaArchive jpaModel : jpaModels) {
            jpaModel.delete("/META-INF/persistence.xml");
            jpaModel.addAsResource("META-INF/persistence-model-jpa.xml", "META-INF/persistence.xml");
        }

        //war.delete("/WEB-INF/classes/META-INF/persistence.xml");
        //war.addAsResource("META-INF/persistence-auth-server.xml", "META-INF/persistence.xml");
    }

    /**
     * Removes original persistence.xml files from unfiedpush-model-jpa JAR and from the war and replaces them with
     * custom ones. This way we change what kind of storage is used.
     *
     * @param war WebArchive to be modified.
     */
    private static void replacePersistenceInWarPreModularization(WebArchive war) {
        // replace original persistence.xml with testing one
        war.delete("/WEB-INF/classes/META-INF/persistence.xml");
        // testing persistence
        war.addAsResource("META-INF/persistence-pre-mod.xml", "META-INF/persistence.xml");
    }

    /**
     * Gets Unified Push Server from remote repository. If no version has been specified,
     * latest version in repository will be used.
     */
    private static WebArchive remoteUnifiedPushServer() {
        final String upsCanonicalCoordinate = "org.jboss.aerogear.unifiedpush:unifiedpush-server:war:%s";

        ConfigurableMavenResolverSystem resolver = Maven.configureResolver()
                .withRemoteRepo(MavenRemoteRepositories.createRemoteRepository("remote_ups", getUpsRemoteUrl(),
                        "default"))
                .withMavenCentralRepo(false);

        MavenCoordinate upsCoordinate;
        String upsVersion = System.getProperty(PROPERTY_UPS_VERSION);
        if (upsVersion == null || upsVersion.length() == 0) {
            upsCoordinate = resolver
                    .resolveVersionRange(String.format(upsCanonicalCoordinate, UPS_MINIMUM_VERSION))
                    .getHighestVersion();

            LOGGER.log(Level.INFO, "Unified Push Server version not specified. Using repository''s latest version " +
                    "\"{0}\". You can override it by -D{1}", new Object[] { upsCoordinate.getVersion(),
                    PROPERTY_UPS_VERSION });
        } else {
            upsCoordinate = MavenCoordinates.createCoordinate(String.format(upsCanonicalCoordinate, upsVersion));
        }

        LOGGER.log(Level.INFO, "Resolving UnifiedPush Server using coordinates: {0}", upsCoordinate.toCanonicalForm());

        File warFile = resolver
                .resolve(upsCoordinate.toCanonicalForm())
                .withoutTransitivity()
                .asSingleFile();

        // https://issues.jboss.org/browse/WFK2-61
        return ShrinkWrap.create(ZipImporter.class, "ag-push.war").importFrom(warFile).as(WebArchive.class);
    }

    /**
     * Compiles and returns Unified Push Server from local filesystem. The location of the pom.xml can be altered.
     */
    private static WebArchive localUnfiedPushServer() {
        buildLocalServerIfNeeded();

        File[] serverWarFiles = getUpsServerWarFiles();
        if (serverWarFiles.length == 0) {
            throw new IllegalStateException("No war file found in directory '" +
                    getUpsServerTargetDirectory().getAbsolutePath() + "'. Please check that 'mvn clean package' " +
                    "inside the ups server directory will result in creation of .war file.");
        }


        return ShrinkWrap.create(ZipImporter.class, "ag-push.war").importFrom(serverWarFiles[0]).as(WebArchive.class);
    }

    public static WebArchive authServer() {
        String upsSource = getUpsSource();
        WebArchive war;
        if (upsSource.equalsIgnoreCase(UPS_SOURCE_REMOTE)) {
            war = remoteAuthServer();
        } else if (upsSource.equalsIgnoreCase(UPS_SOURCE_LOCAL)) {
            war = localAuthServer();
        } else {
            throw new IllegalArgumentException("Unsupported source of Unified Push Server WAR: " + upsSource + "!");
        }

        replaceAuthServerPersistence(war);
        fixAerogearAuthServerConfiguration(war);

        return war;
    }

    private static void replaceAuthServerPersistence(WebArchive war) {
        war.delete("/WEB-INF/classes/META-INF/persistence.xml");
        war.addAsResource("META-INF/persistence-auth-server.xml", "META-INF/persistence.xml");
    }

    private static void fixAerogearAuthServerConfiguration(WebArchive war) {
        Node upsRealm = war.get("WEB-INF/ups-realm.json");

        JSONTokener tokener = new JSONTokener(upsRealm.getAsset().openStream());

        JSONObject config = new JSONObject(tokener);

        // Enable Direct Grant API
        config.put("passwordCredentialGrantAllowed", true);

        // Any required action would prevent us to login
        JSONArray users = config.optJSONArray("users");
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);

            user.remove("requiredActions");
        }

        if (!config.has("scopeMappings")) {
            config.put("scopeMappings", new JSONArray());
        }

        JSONArray scopeMappings = config.optJSONArray("scopeMappings");

        JSONObject integrationTestsScopeMapping = new JSONObject()
                .put("client", "integration-tests")
                .put("roles", new String[] { "user", "admin" });

        scopeMappings.put(integrationTestsScopeMapping);

        if (!config.has("oauthClients")) {
            config.put("oauthClients", new JSONArray());
        }

        JSONArray oauthClients = config.optJSONArray("oauthClients");

        JSONObject integrationTestsClient = new JSONObject()
                .put("name", "integration-tests")
                .put("enabled", true)
                .put("publicClient", true)
                .put("directGrantsOnly", true);

        oauthClients.put(integrationTestsClient);

        System.out.println(config.toString(1));

        war.add(new StringAsset(config.toString()), upsRealm.getPath());
    }

    private static WebArchive remoteAuthServer() {
        throw new UnsupportedOperationException("Remote auth server not yet supported!");
    }

    private static WebArchive localAuthServer() {
        buildLocalServerIfNeeded();

        File[] authServerWarFiles = getAuthServerWarFiles();
        if (authServerWarFiles.length == 0) {
            throw new IllegalStateException("No war file found in directory '" + getAuthServerTargetDirectory()
                    .getAbsolutePath() + "'. Please check that 'mvn clean package' inside the ups auth-server " +
                    "directory will result in creation of .war file.");
        }

        return ShrinkWrap.create(ZipImporter.class, "auth-server.war").importFrom(authServerWarFiles[0])
                .as(WebArchive.class);
    }

    private static void buildLocalServerIfNeeded() {
        if (!mavenBuildInvoked.get()) {
            LOGGER.log(Level.INFO, "Building UnifiedPush Server from sources at: {0}",
                    getUpsParentDirectory().getAbsolutePath());

            try {
                Tasks.prepare(CommandTool.class)
                        .workingDir(getUpsParentDirectory().getAbsolutePath())
                        .programName("mvn")
                        .parameters("clean", "package", "-DskipTests", "-Dmaven.javadoc.skip=true",
                                getActiveProfilesAsMavenParameter())
                        .execute()
                        .await();
            } catch (ExecutionException e) {
                LOGGER.log(Level.WARNING, "Could not package UnifiedPush Server WAR. It is possible that you do not " +
                        "have Maven on PATH. Assuming you did compile UnifiedPush yourself and resuming tests.", e);
            }
            mavenBuildInvoked.set(true);
        }
    }

    /**
     * Returns an array of profile names for maven build. This is currently only to pass in code-coverage profile
     * for
     * ups build.
     */
    private static String[] getActiveProfiles() {
        List<String> activeProfiles = new ArrayList<String>();

        if (isCodeCoverageActive()) {
            activeProfiles.add("code-coverage");
        }

        return activeProfiles.toArray(new String[activeProfiles.size()]);
    }

    private static String getActiveProfilesAsMavenParameter() {
        StringBuilder profileBuilder = new StringBuilder("-P");
        int i = 0;
        for (String profile : getActiveProfiles()) {
            if (i++ > 0) {
                profileBuilder.append(',');
            }
            profileBuilder.append(profile);
        }

        return i > 0 ? profileBuilder.toString() : "";
    }

    private static String getUpsSource() {
        String upsSource = System.getProperty(PROPERTY_UPS_SOURCE);
        if (upsSource == null || upsSource.length() == 0) {
            // FIXME what should be the default behavior?
            upsSource = UPS_SOURCE_DEFAULT;
            LOGGER.log(Level.INFO, "Unified Push Server WAR source not specified. Using default source \"{0}\". You " +
                    "can override it by -D{1}", new Object[] { upsSource, PROPERTY_UPS_SOURCE });
        }
        return upsSource;
    }

    private static String getUpsRemoteUrl() {
        String remoteRepository = System.getProperty(PROPERTY_UPS_REMOTE_URL);
        if (remoteRepository == null || remoteRepository.length() == 0) {
            remoteRepository = UPS_REMOTE_URL_DEFAULT;
            LOGGER.log(Level.INFO, "Unified Push Server remote repository url not specified. Using default \"{0}\". " +
                    "You can override it by -D{1}", new Object[] { remoteRepository, PROPERTY_UPS_REMOTE_URL });
        }
        return remoteRepository;
    }

    private static File getUpsLocalPomFile() {
        String upsLocalPomPath = System.getProperty(PROPERTY_UPS_LOCAL_POM, UPS_LOCAL_POM_DEFAULT);
        return new File(upsLocalPomPath);
    }

    private static File getUpsParentDirectory() {
        return getUpsLocalPomFile().getParentFile();
    }

    private static File getUpsServerDirectory() {
        return new File(getUpsParentDirectory(), "server");
    }

    private static File getUpsServerTargetDirectory() {
        return new File(getUpsServerDirectory(), "target");
    }

    private static File[] getUpsServerWarFiles() {
        return getUpsServerTargetDirectory().listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".war") && pathname.isFile() && pathname.canRead();
            }
        });
    }

    private static File getAuthServerDirectory() {
        return new File(getUpsParentDirectory(), "auth-server");
    }

    private static File getAuthServerTargetDirectory() {
        return new File(getAuthServerDirectory(), "target");
    }

    private static File[] getAuthServerWarFiles() {
        return getAuthServerTargetDirectory().listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".war") && pathname.isFile() && pathname.canRead();
            }
        });
    }

    /**
     * @return True if code-coverage.active property is set to 'true', false otherwise.
     */
    private static boolean isCodeCoverageActive() {
        String codeCoverageActive = System.getProperty("code-coverage.active");
        return codeCoverageActive != null && codeCoverageActive.equals("true");
    }
}
