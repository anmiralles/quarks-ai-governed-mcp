package me.amiralles.applications.boundary;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import me.amiralles.applications.entity.Application;
import me.amiralles.applications.entity.ApplicationStatus;
import me.amiralles.applications.entity.EntityNotFoundException;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/applications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApplicationsResource {

    private static final Logger LOGGER = Logger.getLogger(ApplicationsResource.class.getName());

    @GET
    public List<Application> getAllApplications() {
        LOGGER.info("Received request to retrieve all applications");
        return Application.listAll();
    }

    @GET
    @Path("/application/{id}")
    public Application getApplicationById(@PathParam("id") Long id) {
        LOGGER.info("Received request to retrieve application with id: " + id);
        Application application = Application.findById(id);
        if (application == null) {
            LOGGER.warn("Application not found with id: " + id);
            throw new EntityNotFoundException("Application not found with id: " + id);
        }
        return application;
    }

    @GET
    @Path("/country/{countryName}")
    public List<Application> getApplicationsByCountry(@PathParam("countryName") String countryName) {
        LOGGER.info("Received request to retrieve applications by country: " + countryName);
        List<Application> applications = Application.findByCountry(countryName);
        LOGGER.info("Found " + applications.size() + " applications for country: " + countryName);
        return applications;
    }

    @GET
    @Path("/country/{countryName}/status/{status}")
    public List<Application> getApplicationsByCountryAndStatus(@PathParam("countryName") String countryName, @PathParam("status") ApplicationStatus status) {
        LOGGER.info("Received request to retrieve applications by country: " + countryName + " and status: " + status);
        List<Application> applications = Application.findByCountryAndStatus(countryName, status);
        LOGGER.info("Found " + applications.size() + " applications for country: " + countryName + " and status: " + status);
        return applications;
    }

    @POST
    @Transactional
    public String addApplication(Application application) {
        LOGGER.infof("Received request to create new application: %s %s %s %s",
                application.name, application.surname, application.personalId, application.country);
        application.status = ApplicationStatus.STARTED;
        application.persist();
        LOGGER.info("Application successfully persisted to database");
        return application + " processed";
    }
}
