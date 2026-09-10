package com.potg.don.transaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import com.potg.don.transaction.client.CsvClient;

/** The entire client is a Mockito replacement; no method can execute real HTTP. */
final class StrictCsvClientSupport {

	private StrictCsvClientSupport() {
	}

	static void resetToRejectEveryExternalMethod(CsvClient client) {
		reset(client);
		doAnswer(invocation -> { throw unexpected("uploadCsv"); }).when(client).uploadCsv(any(), any());
		doAnswer(invocation -> { throw unexpected("changeCsv"); }).when(client).changeCsv(any(), any(), any());
		doAnswer(invocation -> { throw unexpected("triggerAnalysis"); }).when(client).triggerAnalysis(any());
		doAnswer(invocation -> { throw unexpected("getCsvStatus"); }).when(client).getCsvStatus(any());
		doAnswer(invocation -> { throw unexpected("getBaseline"); }).when(client).getBaseline(any());
		clearInvocations(client);
	}

	static int calls(CsvClient client) {
		return mockingDetails(client).getInvocations().size();
	}

	private static AssertionError unexpected(String method) {
		return new AssertionError("Unconfigured CsvClient double call: " + method);
	}
}
