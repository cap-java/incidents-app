using { sap.capire.incidents as my } from '../db/schema';

/**
 * Service used by support personell, i.e. the incidents' 'processors'.
 */
@abstract
service ProcessorService {
  entity Incidents as projection on my.Incidents;
  entity Customers as projection on my.Customers;
}

/**
 * Service used by administrators to manage customers and incidents.
 */
service UiService {
  entity Customers as projection on ProcessorService.Customers;
  entity Incidents as projection on ProcessorService.Incidents;
}

service ApiService {
  entity Customers @readonly as projection on ProcessorService.Customers;
  entity Incidents @readonly as projection on ProcessorService.Incidents;
}

annotate UiService.Incidents with @odata.draft.enabled;
