package org.jboss.aerogear.unifiedpush.test;

import java.net.URL;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
public abstract class GenericSimpleUnifiedPushTest {

    @ArquillianResource
    protected URL root;

    @Deployment(name = Deployments.AG_PUSH, testable = false)
    @TargetsContainer("main-server-group")
    public static WebArchive createDeployment() {
        return Deployments.unifiedPushServer();
    }

    @Deployment(name = Deployments.AUTH_SERVER, testable = false)
    @TargetsContainer("main-server-group")
    public static WebArchive createAuthServerDeployment() {
        return Deployments.authServer();
    }

    protected abstract String getContextRoot();
}
