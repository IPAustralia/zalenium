package de.zalando.ep.zalenium.container;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.openshift.client.OpenShiftClient;
import okhttp3.Response;

public class KubernetesContainerClient implements ContainerClient {
    
    private static final String SELENIUM_NODE_NAME = "seleniumNodeName";

    private static KubernetesContainerClient instance;
    
    private static final Logger logger = Logger.getLogger(KubernetesContainerClient.class.getName());
    
    private final KubernetesClient client;
    @SuppressWarnings("unused")
    private final OpenShiftClient oClient;
    
    private String hostname;
    
    private String zaleniumServiceName;
    
    private final Pod zaleniumPod;
//    private static

    private final Map<String, String> createdByZaleniumMap;
    private final Map<String, String> appLabelMap;
    private final Map<String, String> deploymentConfigLabelMap;
    
    public static KubernetesContainerClient getInstance() {
        if (instance == null) {
            synchronized (KubernetesContainerClient.class) {
                if(instance == null){
                    instance = new KubernetesContainerClient();
                }
            }
        }
        
        return instance;
    }
    
    private KubernetesContainerClient() {
        logger.info("Initialising Kubernetes support");
        
        
        client = new DefaultKubernetesClient();
        String kubernetesFlavour;
        if (client.isAdaptable(OpenShiftClient.class)) {
            oClient = client.adapt(OpenShiftClient.class);
            kubernetesFlavour = "OpenShift";
        }
        else {
            kubernetesFlavour = "Vanilla Kubernetes";
            oClient = null;
        }
        
        // Lookup our current hostname, this lets us lookup ourselves via the kubernetes api
        hostname = findHostname();
        
        zaleniumPod = client.pods().withName(hostname).get();
        
        String appName = zaleniumPod.getMetadata().getLabels().get("app");
        String deploymentConfig = zaleniumPod.getMetadata().getLabels().get("deploymentconfig");
        
        appLabelMap = new HashMap<>();
        appLabelMap.put("app", appName);
        
        deploymentConfigLabelMap = new HashMap<>();
        deploymentConfigLabelMap.put("deploymentconfig", deploymentConfig);
        
        createdByZaleniumMap = new HashMap<>();
        createdByZaleniumMap.put("createdBy", appName);
        
        // Lets assume that the zalenium service name is the same as the app name.
        zaleniumServiceName = appName;
        
        logger.log(Level.INFO,
                   "Kubernetes support initialised.\n\tPod name: {0}\n\tapp label: {1}\n\tzalenium service name: {2}\n\tKubernetes flavour: {3}",
                   new Object[] { hostname, appName, zaleniumServiceName, kubernetesFlavour });
        
    }

    private String findHostname() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException e) {
            hostname = null;
        }
        
        return hostname;
    }

    @Override
    public void setNodeId(String nodeId) {
        // We don't care about the nodeId, as it's essentially the same as the containerId, which is passed in where necessary.
    }

    @Override
    public InputStream copyFiles(String containerId,
                                 String folderName) {

        ExecWatch exec = client.pods().withName(containerId).redirectingOutput().exec("tar -C " + folderName + " -c");
        
        // We need to wrap the InputStream so that when the stdout is closed, then the underlying ExecWatch is closed
        // also. This will cleanup any Websockets connections.
        ChainedCloseInputStreamWrapper inputStreamWrapper = new ChainedCloseInputStreamWrapper(exec.getOutput(), exec);
        
        return inputStreamWrapper;
    }

    @Override
    public void stopContainer(String containerId) {
        client.pods().withName(containerId).delete();
        client.services().withName(containerId).delete();
    }

    @Override
    public void executeCommand(String containerId, String[] command, boolean waitForExecution) {
        // TODO Auto-generated method stub
        final CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        logger.log(Level.INFO, () -> String.format("%s %s", containerId, Arrays.toString(command)));
        ExecWatch exec = client.pods().withName(containerId).writingOutput(baos).writingError(baos).usingListener(new ExecListener() {
            
            @Override
            public void onOpen(Response response) {
            }
            
            @Override
            public void onFailure(Throwable t,
                                  Response response) {
                logger.log(Level.FINE, t, () -> String.format("%s Failed to execute command %s", containerId, Arrays.toString(command)));
                latch.countDown();
            }
            
            @Override
            public void onClose(int code,
                                String reason) {
                latch.countDown();
            }
        }).exec(command);

        
        try {
            latch.await();
        }
        catch (InterruptedException e) {
        }
        finally {
            exec.close();
        }
        
        logger.log(Level.INFO, () -> String.format("%s %s", containerId, baos.toString()));
    }

    @Override
    public String getLatestDownloadedImage(String imageName) {
        // TODO Maybe do something here later, try and get an updated version
        // but lets just pass through at the moment
        return imageName;
    }

    @Override
    public String getLabelValue(String image,
                                String label) {
        /*ImageStreamTag imageStreamTag = oClient.imageStreamTags().withName(image).get();
        imageStreamTag.getImage().getDockerImageConfig()*/
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getRunningContainers(String image) {
        PodList list = client.pods().withLabels(createdByZaleniumMap).list();
        
        return list.getItems().size();
    }

    @Override
    public void createContainer(String zaleniumContainerName, String image, Map<String, String> envVars,
                                String nodePort) {
        String containerId = String.format("%s-%s", zaleniumContainerName, nodePort);
        
        // Convert the environment variables into the kubernetes format.
        List<EnvVar> flattenedEnvVars = envVars.entrySet().stream()
                                            .map(e -> new EnvVar(e.getKey(), e.getValue(), null))
                                            .collect(Collectors.toList());
        
        Map<String, String> podSelector = new HashMap<String, String>();
        // In theory we could use the actual container name, but in the future the name might be generated by kubernetes
        // alternately on registration of the node, the label could be updated to the hostname.  But a bug in Openshift/Kubernetes v1.4.0 seems to prevent this
        podSelector.put(SELENIUM_NODE_NAME, UUID.randomUUID().toString());
        
        client.pods()
            .createNew()
            .withNewMetadata()
                .withName(containerId)
                .addToLabels(createdByZaleniumMap)
                .addToLabels(appLabelMap)
                .addToLabels(podSelector)
            .endMetadata()
            .withNewSpec()
                // Add a memory volume that we can use for /dev/shm
                .addNewVolume()
                    .withName("dshm")
                    .withNewEmptyDir()
                        .withMedium("Memory")
                    .endEmptyDir()
                .endVolume()
                .addNewContainer()
                    .withName("selenium-node")
                    .withImage(image)
                    .addAllToEnv(flattenedEnvVars)
                    .addNewVolumeMount()
                        .withName("dshm")
                        .withMountPath("/dev/shm")
                    .endVolumeMount()
                .endContainer()
                .withRestartPolicy("Never")
            .endSpec()
            .done();

    }

    @Override
    public void initialiseContainerEnvironment() {
        // Delete any leftover pods from a previous time
        deleteSeleniumPods();
        
        // Register a shutdown hook to cleanup pods
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            this.deleteSeleniumPods();
         }));
    }

    private void deleteSeleniumPods() {
        logger.info("About to clean up any left over selenium pods created by zalenium");
        client.pods().withLabels(createdByZaleniumMap).delete();
        client.services().withLabels(createdByZaleniumMap).delete();
    }

    @Override
    public ContainerClientRegistration registerNode(String zaleniumContainerName, URL remoteHost) {
        String podIpAddress = remoteHost.getHost();

        // The only way to lookup a pod name by IP address is by looking at all pods in the namespace it seems. 
        PodList list = client.pods().withLabels(createdByZaleniumMap).list();
        
        String containerId = null;
        Pod currentPod = null;
        for (Pod pod : list.getItems()) {
             
            if (podIpAddress.equals(pod.getStatus().getPodIP())) {
                containerId = pod.getMetadata().getName();
                currentPod = pod;
                break;
            }
        }
        
        if (containerId == null) {
            throw new IllegalStateException("Unable to locate pod by ip address, registration will fail");
        }
        
        List<EnvVar> podEnvironmentVariables = currentPod.getSpec().getContainers().iterator().next().getEnv();
        EnvVar noVncPort = podEnvironmentVariables.stream().filter(env -> "NOVNC_PORT".equals(env.getName())).findFirst().get();
        Integer noVncPortInt = Integer.decode(noVncPort.getValue());
        
        String seleniumNodeNameValue = currentPod.getMetadata().getLabels().get(SELENIUM_NODE_NAME);
        
        // Create a service so that we locate novnc
        Service service = client.services()
            .createNew()
            .withNewMetadata()
                .withName(containerId)
                .withLabels(appLabelMap)
                .withLabels(createdByZaleniumMap)
            .endMetadata()
            .withNewSpec()
                .withType("NodePort")
                .addNewPort()
                    .withName("novnc")
                    .withProtocol("TCP")
                    .withPort(noVncPortInt)
                    .withNewTargetPort(noVncPortInt)
                .endPort()
                .addToSelector(SELENIUM_NODE_NAME, seleniumNodeNameValue)
            .endSpec()
            .done();
        
        Integer nodePort = service.getSpec().getPorts().get(0).getNodePort();
        
        ContainerClientRegistration registration = new ContainerClientRegistration();
        registration.setContainerId(containerId);

        registration.setNoVncPort(nodePort);
        
        return registration;
    }
    
    private static class ChainedCloseInputStreamWrapper extends InputStream {
        
        private InputStream delegate;
        private Closeable resourceToClose;
        
        public ChainedCloseInputStreamWrapper(InputStream delegate, Closeable resourceToClose) {
            this.delegate = delegate;
            this.resourceToClose = resourceToClose;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        public int available() throws IOException {
            return delegate.available();
        }

        public void close() throws IOException {
            logger.info("Shutdown called!");
            delegate.close();
            
            // Close our dependent resource
            resourceToClose.close();
        }

        public boolean equals(Object o) {
            return delegate.equals(o);
        }

        public int hashCode() {
            return delegate.hashCode();
        }

        public int read(byte[] array) throws IOException {
            return delegate.read(array);
        }

        public int read(byte[] array,
                        int n,
                        int n2) throws IOException {
            return delegate.read(array, n, n2);
        }

        public long skip(long n) throws IOException {
            return delegate.skip(n);
        }

        public void mark(int n) {
            delegate.mark(n);
        }

        public void reset() throws IOException {
            delegate.reset();
        }

        public boolean markSupported() {
            return delegate.markSupported();
        }

        public String toString() {
            return delegate.toString();
        }
        
    }
}
