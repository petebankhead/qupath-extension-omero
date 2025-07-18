import qupath.ext.omero.core.*
import qupath.lib.images.servers.*

/*
 * This script browses an OMERO server and prints its projects, datasets, and images.
 * An image is also opened and its metadata is printed.
 */

// Create a connection to an OMERO server
def serverURL = "https://idr.openmicroscopy.org/"
def credentials = new Credentials()                                                     // to skip authentication and use the public account
//def credentials = new Credentials("some_username", "some_password".toCharArray())     // to authenticate with a regular account
def client = Client.createOrGet(serverURL, credentials, null)

// List projects of the OMERO server
def projects = client.getApisHandler().getProjects().get()
projects.forEach(it -> {
    println it
})

// List datasets belonging to one project
def projectID = 101
def datasets = client.getApisHandler().getDatasets(projectID).get()
datasets.forEach(it -> {
    println it
})

// List images belonging to one dataset
def datasetID = 369
def images = client.getApisHandler().getImages(datasetID).get()
images.forEach(it -> {
    println it
})

// Open an image and print its metadata
def imageID = 1920093
def image = client.getApisHandler().getImage(imageID).get()
def imageURI = client.getApisHandler().getEntityUri(image)
ImageServer<BufferedImage> server = ImageServerProvider.buildServer(imageURI, BufferedImage.class)
println "Image metadata: " + server.getMetadata()

// Close image
server.close()

// Close client connection. This is needed to release resources on the OMERO server
client.close()