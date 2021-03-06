/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.legacyui;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.PatientState;
import org.openmrs.ProgramWorkflow;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.Provider;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.OrderService;
import org.openmrs.api.ProviderService;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.transaction.annotation.Transactional;

public class GeneralUtils {
	
	private static final Log log = LogFactory.getLog(GeneralUtils.class);
	
	/**
	 * Get the concept by id, the id can either be 1)an integer id like 5090 or 2)mapping type id
	 * like "XYZ:HT" or 3)uuid like "a3e12268-74bf-11df-9768-17cfc9833272"
	 * 
	 * @param id
	 * @return the concept if exist, else null
	 * @should find a concept by its conceptId
	 * @should find a concept by its mapping
	 * @should find a concept by its uuid
	 * @should return null otherwise
	 * @should find a concept by its mapping with a space in between
	 */
	public static Concept getConcept(String id) {
		Concept cpt = null;
		
		if (id != null) {
			
			// see if this is a parseable int; if so, try looking up concept by id
			try { //handle integer: id
				int conceptId = Integer.parseInt(id);
				cpt = Context.getConceptService().getConcept(conceptId);
				
				if (cpt != null) {
					return cpt;
				}
			}
			catch (Exception ex) {
				//do nothing
			}
			
			// handle  mapping id: xyz:ht
			int index = id.indexOf(":");
			if (index != -1) {
				String mappingCode = id.substring(0, index).trim();
				String conceptCode = id.substring(index + 1, id.length()).trim();
				cpt = Context.getConceptService().getConceptByMapping(conceptCode, mappingCode);
				
				if (cpt != null) {
					return cpt;
				}
			}
			
			//handle uuid id: "a3e1302b-74bf-11df-9768-17cfc9833272", if the id matches a uuid format
			if (isValidUuidFormat(id)) {
				cpt = Context.getConceptService().getConceptByUuid(id);
			}
		}
		
		return cpt;
	}
	
	/**
	 * TODO: Patients should actually be allowed to exit multiple times
	 * 
	 * @param patient
	 * @param exitDate
	 * @param cause
	 */
	private static void saveReasonForExitObs(Patient patient, Date exitDate, Concept cause) throws APIException {
		
		if (patient == null)
			throw new APIException("Patient supplied to method is null");
		if (exitDate == null)
			throw new APIException("Exit date supplied to method is null");
		if (cause == null)
			throw new APIException("Cause supplied to method is null");
		
		// need to make sure there is an Obs that represents the patient's
		// exit
		log.debug("Patient is exiting, so let's make sure there's an Obs for it");
		
		String codProp = Context.getAdministrationService().getGlobalProperty("concept.reasonExitedCare");
		Concept reasonForExit = Context.getConceptService().getConcept(codProp);
		
		if (reasonForExit != null) {
			List<Obs> obssExit = Context.getObsService().getObservationsByPersonAndConcept(patient, reasonForExit);
			if (obssExit != null) {
				if (obssExit.size() > 1) {
					log.error("Multiple reasons for exit (" + obssExit.size() + ")?  Shouldn't be...");
				} else {
					Obs obsExit = null;
					if (obssExit.size() == 1) {
						// already has a reason for exit - let's edit it.
						log.debug("Already has a reason for exit, so changing it");
						
						obsExit = obssExit.iterator().next();
						
					} else {
						// no reason for exit obs yet, so let's make one
						log.debug("No reason for exit yet, let's create one.");
						
						obsExit = new Obs();
						obsExit.setPerson(patient);
						obsExit.setConcept(reasonForExit);
						
						Location loc = Context.getLocationService().getDefaultLocation();
						
						if (loc != null)
							obsExit.setLocation(loc);
						else
							log.error("Could not find a suitable location for which to create this new Obs");
					}
					
					if (obsExit != null) {
						// put the right concept and (maybe) text in this
						// obs
						obsExit.setValueCoded(cause);
						obsExit.setValueCodedName(cause.getName()); // ABKTODO: presume current locale?
						obsExit.setObsDatetime(exitDate);
						Context.getObsService().saveObs(obsExit, "updated by PatientService.saveReasonForExit");
					}
				}
			}
		} else {
			log.debug("Reason for exit is null - should not have gotten here without throwing an error on the form.");
		}
		
	}
	
	/**
	 * Attempts to transition the PatientProgram to the passed {@link ProgramWorkflowState} on the
	 * passed {@link Date} by ending the most recent {@link PatientState} in the
	 * {@link PatientProgram} and creating a new one with the passed {@link ProgramWorkflowState}
	 * This will throw an IllegalArgumentException if the transition is invalid
	 * 
	 * @param programWorkflowState - The {@link ProgramWorkflowState} to transition to
	 * @param onDate - The {@link Date} of the transition
	 * @throws IllegalArgumentException
	 */
	public static void transitionToState(ProgramWorkflowState programWorkflowState, Date onDate,
	        PatientProgram patientProgram) {
		PatientState lastState = patientProgram.getCurrentState(programWorkflowState.getProgramWorkflow());
		if (lastState != null && onDate == null) {
			throw new IllegalArgumentException("You can't change from a non-null state without giving a change date");
		}
		if (lastState != null && lastState.getEndDate() != null) {
			throw new IllegalArgumentException("You can't change out of a state that has an end date already");
		}
		if (lastState != null && lastState.getStartDate() != null
		        && OpenmrsUtil.compare(lastState.getStartDate(), onDate) > 0) {
			throw new IllegalArgumentException("You can't change out of a state before that state started");
		}
		if (lastState != null
		        && !programWorkflowState.getProgramWorkflow().isLegalTransition(lastState.getState(), programWorkflowState)) {
			throw new IllegalArgumentException("You can't change from state " + lastState.getState() + " to "
			        + programWorkflowState);
		}
		if (lastState != null) {
			lastState.setEndDate(onDate);
		}
		
		PatientState newState = new PatientState();
		newState.setPatientProgram(patientProgram);
		newState.setState(programWorkflowState);
		newState.setStartDate(onDate);
		
		if (newState.getPatientProgram() != null && newState.getPatientProgram().getDateCompleted() != null) {
			newState.setEndDate(newState.getPatientProgram().getDateCompleted());
		}
		
		if (programWorkflowState.getTerminal()) {
			patientProgram.setDateCompleted(onDate);
		}
		
		patientProgram.getStates().add(newState);
	}
	
	/**
	 * @see org.openmrs.api.ProgramWorkflowService#triggerStateConversion(org.openmrs.Patient,
	 *      org.openmrs.Concept, java.util.Date)
	 */
	public static void triggerStateConversion(Patient patient, Concept trigger, Date dateConverted) {
		
		// Check input parameters
		if (patient == null)
			throw new APIException("Attempting to convert state of an invalid patient");
		if (trigger == null)
			throw new APIException("Attempting to convert state for a patient without a valid trigger concept");
		if (dateConverted == null)
			throw new APIException("Invalid date for converting patient state");
		
		for (PatientProgram patientProgram : Context.getProgramWorkflowService().getPatientPrograms(patient, null, null,
		    null, null, null, false)) {
			Set<ProgramWorkflow> workflows = patientProgram.getProgram().getWorkflows();
			for (ProgramWorkflow workflow : workflows) {
				// (getWorkflows() is only returning over nonretired workflows)
				PatientState patientState = patientProgram.getCurrentState(workflow);
				
				// #1080 cannot exit patient from care  
				// Should allow a transition from a null state to a terminal state
				// Or we should require a user to ALWAYS add an initial workflow/state when a patient is added to a program
				ProgramWorkflowState currentState = (patientState != null) ? patientState.getState() : null;
				ProgramWorkflowState transitionState = workflow.getState(trigger);
				
				log.debug("Transitioning from current state [" + currentState + "]");
				log.debug("|---> Transitioning to final state [" + transitionState + "]");
				
				if (transitionState != null && workflow.isLegalTransition(currentState, transitionState)) {
					transitionToState(transitionState, dateConverted, patientProgram);
					log.debug("State Conversion Triggered: patientProgram=" + patientProgram + " transition from "
					        + currentState + " to " + transitionState + " on " + dateConverted);
				}
			}
			
			// #1068 - Exiting a patient from care causes "not-null property references
			// a null or transient value: org.openmrs.PatientState.dateCreated". Explicitly
			// calling the savePatientProgram() method will populate the metadata properties.
			// 
			// #1067 - We should explicitly save the patient program rather than let 
			// Hibernate do so when it flushes the session.
			Context.getProgramWorkflowService().savePatientProgram(patientProgram);
		}
	}
	
	/**
	 * @see OrderExtensionService#getProviderForUser(User)
	 */
	@Transactional(readOnly = true)
	public static Provider getProviderForUser(User user) {
		ProviderService ps = Context.getProviderService();
		Collection<Provider> providers = ps.getProvidersByPerson(user.getPerson(), true);
		if (providers.isEmpty()) {
			throw new IllegalStateException("User " + user + " has no provider accounts, unable to create orders");
		}
		return providers.iterator().next();
	}
	
	/**
	 * Discontinues all current orders for the given <code>patient</code>
	 * 
	 * @param patient
	 * @param discontinueReason
	 * @param discontinueDate
	 * @see OrderService#discontinueOrder(org.openmrs.Order, Concept, Date)
	 * @should discontinue all orders for the given patient if none are yet discontinued
	 * @should not affect orders that were already discontinued on the specified date
	 * @should not affect orders that end before the specified date
	 * @should not affect orders that start after the specified date
	 */
	public static void discontinueAllDrugOrders(Patient patient, Concept discontinueReason, Date discontinueDate) {
		if (log.isDebugEnabled())
			log.debug("In discontinueAll with patient " + patient + " and concept " + discontinueReason + " and date "
			        + discontinueDate);
		
		OrderService orderService = Context.getOrderService();
		int durgOrderType = 2; //Default OpenMRS core drug order type ID
		try {
			durgOrderType = Integer.valueOf(Context.getAdministrationService().getGlobalProperty(
			    "orderextension.drugOrderType"));
		}
		catch (Exception e) {
			log.error("orderextension.drugOrderType global property value should be an integer");
		}
		List<Order> drugOrders = orderService.getOrders(patient, orderService.getCareSetting(2), Context.getOrderService()
		        .getOrderType(durgOrderType), false);
		// loop over all of this patient's drug orders to discontinue each
		if (drugOrders != null) {
			for (Order drugOrder : drugOrders) {
				if (log.isDebugEnabled())
					log.debug("discontinuing order: " + drugOrder);
				// do the stuff to the database
				if (drugOrder.isActive() || drugOrder.getEffectiveStopDate() == null) {
					try {
						Context.getOrderService().discontinueOrder(drugOrder, discontinueReason, discontinueDate,
						    getProviderForUser(Context.getAuthenticatedUser()), drugOrder.getEncounter());
					}
					catch (Exception e) {
						e.printStackTrace();
					}
				} else {
					
				}
			}
		}
	}
	
	/**
	 * This is the way to establish that a patient has left the care center. This API call is
	 * responsible for:
	 * <ol>
	 * <li>Closing workflow statuses</li>
	 * <li>Terminating programs</li>
	 * <li>Discontinuing orders</li>
	 * <li>Flagging patient table</li>
	 * <li>Creating any relevant observations about the patient (if applicable)</li>
	 * </ol>
	 * 
	 * @param patient - the patient who has exited care
	 * @param dateExited - the declared date/time of the patient's exit
	 * @param reasonForExit - the concept that corresponds with why the patient has been declared as
	 *            exited
	 * @throws APIException
	 */
	public static void exitFromCare(Patient patient, Date dateExited, Concept reasonForExit) throws APIException {
		
		if (patient == null)
			throw new APIException("Attempting to exit from care an invalid patient. Cannot proceed");
		if (dateExited == null)
			throw new APIException("Must supply a valid dateExited when indicating that a patient has left care");
		if (reasonForExit == null)
			throw new APIException(
			        "Must supply a valid reasonForExit (even if 'Unknown') when indicating that a patient has left care");
		
		// need to create an observation to represent this (otherwise how
		// will we know?)
		saveReasonForExitObs(patient, dateExited, reasonForExit);
		
		// need to terminate any applicable programs
		triggerStateConversion(patient, reasonForExit, dateExited);
		
		// need to discontinue any open orders for this patient
		discontinueAllDrugOrders(patient, reasonForExit, dateExited);
	}
	
	public static boolean isValidUuidFormat(String uuid) {
		return uuid.length() >= 36 && uuid.length() <= 38 && !uuid.contains(" ");
	}
}
