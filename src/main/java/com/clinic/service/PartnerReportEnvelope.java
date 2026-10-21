package com.clinic.service;

import java.io.Serializable;

/**
 * The report object the legacy laboratory bridge hands the clinic for each
 * result: the patient it belongs to and the summary the laboratory wrote.
 */
public record PartnerReportEnvelope(String patientMrn, String summary) implements Serializable {
}
