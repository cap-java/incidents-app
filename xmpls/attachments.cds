using { sap.capire.incidents as my } from '../db/schema';
using { sap.attachments.Attachments } from 'com.sap.cds/cds-feature-attachments';

extend my.Incidents with {
  attachments: Composition of many Attachments;
}

using { ProcessorService as service } from '../app/services';
annotate service.Incidents with @(
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
