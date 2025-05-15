using { sap.capire.incidents as my } from '../db/schema';
using { sap.attachments.Attachments } from 'com.sap.cds/cds-feature-attachments';

extend my.Incidents with {
  attachments: Composition of many Attachments;
}

using { ProcessorService } from '../app/services';
annotate ProcessorService.Incidents with @(
  UI.Facets: [
    ...,
    {
      $Type  : 'UI.ReferenceFacet',
      ID     : 'AttachmentsFacet',
      Label  : '{i18n>attachments}',
      Target : 'attachments/@UI.LineItem'
    }
  ]
);

// Prototype for new version action
entity ProcessorService.Incidents.attachments as projection on my.Incidents.attachments actions {
  @Common.UploadAfterActionTo: ($self.content) // TODO what is expected here?
  @Common.SideEffects #refreshAttachments: {
    TargetEntities: ['in/up_/attachments']
  }
  action createNewVersion(note: String) returns ProcessorService.Incidents.attachments;
};

extend Attachments with @(UI.LineItem: [
  ...,
  {
    $Type : 'UI.DataFieldForAction',
    Label : 'Upload New Version',
    Action : 'ProcessorService.createNewVersion'
  },
]);

extend Attachments with {
  key version: Integer default 0;
}
