package me.amiralles.applications.boundary;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.core.Response;
import me.amiralles.applications.entity.Application;
import me.amiralles.applications.entity.ApplicationStatus;

import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class ApplicationsMcpTools {

    private static final Logger LOGGER = Logger.getLogger(ApplicationsMcpTools.class);

    @Tool(description = "List all asylum requests in the system")
    public List<Application> list_applications() {
        LOGGER.info("MCP Tool: Listing all asylum requests");
        return Application.listAll();
    }

    @Tool(description = "List a personal asylum request by its id")
    public Application get_application_by_id(
            @ToolArg(description = "The id of the asylum request to retrieve", required = true)
            @NotNull
            Long id) {
        LOGGER.infof("MCP Tool: Getting asylum request by id: %d", id);
        return Application.findById(id);
    }

    @Tool(description = "List personal requests filtered by country name")
    public List<Application> get_applications_by_country(
            @ToolArg(description = "The name of the country to filter by", required = true)
            @NotNull
            @Size(max = 20)
            String countryName) {
        LOGGER.infof("MCP Tool: Getting personal requests by country: %s", countryName);
        return Application.findByCountry(countryName);
    }

    @Tool(description = "List personal requests filtered by both country name and request status")
    public List<Application> get_applications_by_country_and_status(
            @ToolArg(description = "The name of the country to filter by", required = true)
            @NotNull
            @Size (max = 20)
            String countryName,
            @ToolArg(description = "The application status to filter by (STARTED, PREPROCESSED, PROCESSED, COMPLETED, CANCELED, ERROR)", required = true) 
            @NotNull 
            ApplicationStatus status) {
        LOGGER.infof("MCP Tool: Getting applications by country: %s and status: %s", countryName, status);
        return Application.findByCountryAndStatus(countryName, status);
    }

    @Tool(description = "Add personal request to the system")
    public Response add_application(
            @ToolArg(description = "The data for the personal request (name, surname, personalId and country)", required = true) 
            @NotNull 
            Application application) {
        LOGGER.infof("Received request to create new application: %s %s %s",
                application.name, application.surname, application.personalId);
        Application.persist(application);
        return Response.status(Response.Status.CREATED).build();
    }

}