package customer.incident_management.handler;

import cds.gen.processorservice.Incidents;
import cds.gen.processorservice.IncidentsAttachments;
import cds.gen.processorservice.IncidentsAttachmentsCreateNewVersionContext;
import cds.gen.processorservice.IncidentsAttachments_;
import cds.gen.processorservice.ProcessorService_;
import cds.gen.sap.attachments.Attachments;
import cds.gen.sap.capire.incidents.Incidents_;
import com.sap.cds.Result;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.RefBuilder;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.StructuredTypeRef;
import com.sap.cds.ql.cqn.CqnInsert;
import com.sap.cds.ql.cqn.CqnStructuredTypeRef;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.utils.DraftUtils;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ServiceName(ProcessorService_.CDS_NAME)
public class ProcessorServiceHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(ProcessorServiceHandler.class);

  private final PersistenceService db;

  public ProcessorServiceHandler(PersistenceService db) {
    this.db = db;
  }

  @On(entity = IncidentsAttachments_.CDS_NAME)
  public void createNewAttachmentVersion(
      IncidentsAttachmentsCreateNewVersionContext context, CqnStructuredTypeRef ref) {
    CqnService service = (CqnService) context.getService();

    Attachments attachment = service.run(Select.from(ref)).single(Attachments.class);
    Attachments newVersion = Attachments.create();
    newVersion.setId(attachment.getId());
    newVersion.setVersion(attachment.getVersion() + 1);
    newVersion.setNote(context.getNote());

    RefBuilder<StructuredTypeRef> collectionBuilder = CQL.copy(ref);
    collectionBuilder.targetSegment().filter(null);
    StructuredTypeRef collection = collectionBuilder.build();
    CqnInsert insert = Insert.into(collection).entry(newVersion);

    StructuredTypeRef parent = CQL.to(collection.segments().subList(0, collection.size())).asRef();
    Result result;
    if (DraftUtils.isDraftTarget(parent, context.getTarget(), context.getModel())
        && service instanceof DraftService draftService) {
      result = draftService.newDraft(insert);
    } else {
      result = service.run(insert);
    }
    context.setResult(result.single(IncidentsAttachments.class));
  }

  /*
   * Change the urgency of an incident to "high" if the title contains the word "urgent"
   */
  @Before(event = CqnService.EVENT_CREATE)
  public void ensureHighUrgencyForIncidentsWithUrgentInTitle(List<Incidents> incidents) {
    for (Incidents incident : incidents) {
      if (incident.getTitle().toLowerCase(Locale.ENGLISH).contains("urgent")
              && incident.getUrgencyCode() == null
          || !incident.getUrgencyCode().equals("H")) {
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
    Incidents in =
        db.run(Select.from(Incidents_.class).where(i -> i.ID().eq(incident.getId())))
            .single(Incidents.class);
    if (in.getStatusCode().equals("C")) {
      throw new ServiceException(ErrorStatuses.CONFLICT, "Can't modify a closed incident");
    }
  }
}
