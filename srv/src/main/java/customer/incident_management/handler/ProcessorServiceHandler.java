package customer.incident_management.handler;

import cds.gen.processorservice.Incidents;
import cds.gen.processorservice.ProcessorService_;
import cds.gen.sap.capire.incidents.*;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;

@Component
@ServiceName(ProcessorService_.CDS_NAME)
public class ProcessorServiceHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProcessorServiceHandler.class);

    private final PersistenceService db;

    public ProcessorServiceHandler(PersistenceService db) {
        this.db = db;
    }

    /*
     * Change the urgency of an incident to "high" if the title contains the word "urgent"
     */
    @Before(event = CqnService.EVENT_CREATE)
    public void ensureHighUrgencyForIncidentsWithUrgentInTitle(List<Incidents> incidents) {
        for (Incidents incident : incidents) {
            if (incident.getTitle().toLowerCase(Locale.ENGLISH).contains("urgent") &&
                    incident.getUrgencyCode() == null || !incident.getUrgencyCode().equals("H")) {
                incident.setUrgencyCode("H");
                logger.info("Adjusted Urgency for incident '{}' to 'HIGH'.", incident.getTitle());
            }

        }
    }

    /*
     * Handler to avoid updating a "closed" incident
     */
    @Before(event = CqnService.EVENT_UPDATE)
    public void ensureNoUpdateOnClosedIncidents(Incidents incident) {
        Incidents in = db.run(Select.from(Incidents_.class).where(i -> i.ID().eq(incident.getId()))).single(Incidents.class);
        if (in.getStatusCode().equals("C")) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "Can't modify a closed incident");
        }

    }

}
