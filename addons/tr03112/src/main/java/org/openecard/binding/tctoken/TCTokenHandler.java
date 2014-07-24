/****************************************************************************
 * Copyright (C) 2012 HS Coburg.
 * All rights reserved.
 * Contact: ecsec GmbH (info@ecsec.de)
 *
 * This file is part of the Open eCard App.
 *
 * GNU General Public License Usage
 * This file may be used under the terms of the GNU General Public
 * License version 3.0 as published by the Free Software Foundation
 * and appearing in the file LICENSE.GPL included in the packaging of
 * this file. Please review the following information to ensure the
 * GNU General Public License version 3.0 requirements will be met:
 * http://www.gnu.org/copyleft/gpl.html.
 *
 * Other Usage
 * Alternatively, this file may be used in accordance with the terms
 * and conditions contained in a signed written agreement between
 * you and ecsec GmbH.
 *
 ***************************************************************************/

package org.openecard.binding.tctoken;

import generated.TCTokenType;
import iso.std.iso_iec._24727.tech.schema.CardApplicationConnect;
import iso.std.iso_iec._24727.tech.schema.CardApplicationConnectResponse;
import iso.std.iso_iec._24727.tech.schema.CardApplicationDisconnect;
import iso.std.iso_iec._24727.tech.schema.CardApplicationPath;
import iso.std.iso_iec._24727.tech.schema.CardApplicationPathResponse;
import iso.std.iso_iec._24727.tech.schema.CardApplicationPathType;
import iso.std.iso_iec._24727.tech.schema.ConnectionHandleType;
import iso.std.iso_iec._24727.tech.schema.StartPAOSResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import javax.xml.transform.TransformerException;
import org.openecard.addon.AddonManager;
import org.openecard.addon.AddonRegistry;
import org.openecard.addon.Context;
import org.openecard.addon.manifest.AddonSpecification;
import org.openecard.addon.manifest.ProtocolPluginSpecification;
import org.openecard.bouncycastle.crypto.tls.Certificate;
import org.openecard.common.DynamicContext;
import org.openecard.common.ECardConstants;
import org.openecard.common.I18n;
import org.openecard.common.WSHelper;
import org.openecard.common.WSHelper.WSException;
import org.openecard.common.interfaces.Dispatcher;
import org.openecard.common.interfaces.DispatcherException;
import org.openecard.common.sal.state.CardStateEntry;
import org.openecard.common.sal.state.CardStateMap;
import org.openecard.common.util.Pair;
import org.openecard.common.sal.util.InsertCardDialog;
import org.openecard.gui.UserConsent;
import org.openecard.recognition.CardRecognition;
import org.openecard.transport.paos.PAOSException;
import org.openecard.ws.marshal.WSMarshaller;
import org.openecard.ws.marshal.WSMarshallerException;
import org.openecard.ws.marshal.WSMarshallerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Transport binding agnostic TCToken handler. <br/>
 * This handler supports the following transports:
 * <ul>
 * <li>PAOS</li>
 * </ul>
 * <p>
 * This handler supports the following security protocols:
 * <ul>
 * <li>TLS</li>
 * <li>TLS-PSK</li>
 * <li>PLS-PSK-RSA</li>
 * </ul>
 *
 * @author Dirk Petrautzki <petrautzki@hs-coburg.de>
 * @author Moritz Horsch <horsch@cdc.informatik.tu-darmstadt.de>
 */
public class TCTokenHandler {

    private static final Logger logger = LoggerFactory.getLogger(TCTokenHandler.class);
    private final I18n lang = I18n.getTranslation("tctoken");

    private final CardStateMap cardStates;
    private final Dispatcher dispatcher;
    private final UserConsent gui;
    private final CardRecognition rec;
    private final AddonManager manager;

    /**
     * Creates a TCToken handler instances and initializes it with the given parameters.
     *
     * @param ctx Context containing instances to the core modules.
     */
    public TCTokenHandler(Context ctx) {
	this.cardStates = ctx.getCardStates();
	this.dispatcher = ctx.getDispatcher();
	this.gui = ctx.getUserConsent();
	this.rec = ctx.getRecognition();
	this.manager = ctx.getManager();
    }


    /**
     * Gets the first handle of the given card type.
     *
     * @param type The card type to get the first handle for.
     * @return Handle describing the given card type or null if none is present.
     */
    private ConnectionHandleType getFirstHandle(String type) {
	String cardName = rec.getTranslatedCardName(type);
	ConnectionHandleType conHandle = new ConnectionHandleType();
	ConnectionHandleType.RecognitionInfo recInfo = new ConnectionHandleType.RecognitionInfo();
	recInfo.setCardType(type);
	conHandle.setRecognitionInfo(recInfo);
	Set<CardStateEntry> entries = cardStates.getMatchingEntries(conHandle);
	if (entries.isEmpty()) {
	    InsertCardDialog uc = new InsertCardDialog(gui, cardStates, type, cardName);
	    return uc.show();
	} else {
	    return entries.iterator().next().handleCopy();
	}
    }

    private ConnectionHandleType prepareHandle(ConnectionHandleType connectionHandle) throws DispatcherException, InvocationTargetException, WSException {
	// Perform a CardApplicationPath and CardApplicationConnect to connect to the card application
	CardApplicationPath appPath = new CardApplicationPath();
	appPath.setCardAppPathRequest(connectionHandle);
	CardApplicationPathResponse appPathRes = (CardApplicationPathResponse) dispatcher.deliver(appPath);

	// Check CardApplicationPathResponse
	WSHelper.checkResult(appPathRes);

	CardApplicationConnect appConnect = new CardApplicationConnect();
	List<CardApplicationPathType> pathRes;
	pathRes = appPathRes.getCardAppPathResultSet().getCardApplicationPathResult();
	appConnect.setCardApplicationPath(pathRes.get(0));
	CardApplicationConnectResponse appConnectRes;
	appConnectRes = (CardApplicationConnectResponse) dispatcher.deliver(appConnect);
	// Update ConnectionHandle. It now includes a SlotHandle.
	connectionHandle = appConnectRes.getConnectionHandle();

	// Check CardApplicationConnectResponse
	WSHelper.checkResult(appConnectRes);

	return connectionHandle;
    }

    /**
     * Performs the actual PAOS procedure.
     * Connects the given card, establishes the HTTP channel and talks to the server. Afterwards disconnects the card.
     *
     * @param token The TCToken containing the connection parameters.
     * @param connectionHandle The handle of the card that will be used.
     * @return A TCTokenResponse indicating success or failure.
     * @throws DispatcherException If there was a problem dispatching a request from the server.
     * @throws PAOSException If there was a transport error.
     */
    private TCTokenResponse processBinding(TCTokenRequest tokenRequest, ConnectionHandleType connectionHandle)
	    throws PAOSException, DispatcherException {
	TCTokenType token = tokenRequest.getTCToken();
	try {
	    connectionHandle = prepareHandle(connectionHandle);

	    TCTokenResponse response = new TCTokenResponse();
	    response.setRefreshAddress(new URL(token.getRefreshAddress()));
	    response.setCommunicationErrorAddress(token.getCommunicationErrorAddress());
	    response.setResult(WSHelper.makeResultOK());

	    String binding = token.getBinding();
	    if ("urn:liberty:paos:2006-08".equals(binding)) {
		// send StartPAOS
		List<String> supportedDIDs = getSupportedDIDs();
		PAOSTask task = new PAOSTask(dispatcher, connectionHandle, supportedDIDs, tokenRequest);
		FutureTask<StartPAOSResponse> paosTask = new FutureTask<>(task);
		Thread paosThread = new Thread(paosTask, "PAOS");
		paosThread.start();
		if (! tokenRequest.isTokenFromObject()) {
		    // wait for computation to finish
		    waitForTask(paosTask);
		}

		response.setBindingTask(paosTask);
	    } else if ("urn:ietf:rfc:2616".equals(binding)) {
		// no actual binding, just connect via tls and authenticate the user with that connection
		HttpGetTask task = new HttpGetTask(dispatcher, connectionHandle, tokenRequest);
		FutureTask<StartPAOSResponse> tlsTask = new FutureTask<>(task);
		Thread tlsThread = new Thread(tlsTask, "TLS Auth");
		tlsThread.start();
		waitForTask(tlsTask);

		response.setBindingTask(tlsTask);
	    } else {
		// unknown binding
		throw new RuntimeException("Unsupported binding in TCToken.");
	    }

	    return response;
	} catch (WSException ex) {
	    String msg = "Failed to connect to card.";
	    logger.error(msg, ex);
	    throw new DispatcherException(msg, ex);
	} catch (InvocationTargetException ex) {
	    logger.error(ex.getMessage(), ex);
	    throw new DispatcherException(ex);
	} catch (MalformedURLException ex) {
	    logger.error(ex.getMessage(), ex);
	    throw new PAOSException(ex);
	}
    }

    public static void disconnectHandle(Dispatcher dispatcher, ConnectionHandleType connectionHandle)
	   throws DispatcherException {
	try {
	    // disconnect card after authentication
	    CardApplicationDisconnect appDis = new CardApplicationDisconnect();
	    appDis.setConnectionHandle(connectionHandle);
	    dispatcher.deliver(appDis);
	} catch (InvocationTargetException ex) {
	    logger.error(ex.getMessage(), ex);
	    throw new DispatcherException(ex);
	}
    }

    /**
     * Activates the client according to the received TCToken.
     *
     * @param request The activation request containing the TCToken.
     * @return The response containing the result of the activation process.
     * @throws MalformedURLException
     * @throws UnsupportedEncodingException
     * @throws InvalidRedirect
     * @throws CommunicationError Thrown when the process should be terminated after a specified error.
     */
    public TCTokenResponse handleActivate(TCTokenRequest request) throws MalformedURLException,
	    UnsupportedEncodingException, InvalidRedirect, IOException, CommunicationError {
	TCTokenType token = request.getTCToken();
	if (logger.isDebugEnabled()) {
	    try {
		WSMarshaller m = WSMarshallerFactory.createInstance();
		logger.debug("TCToken:\n{}", m.doc2str(m.marshal(token)));
	    } catch (TransformerException | WSMarshallerException ex) {
		// it's no use
	    }
	}

	final DynamicContext dynCtx = DynamicContext.getInstance(TR03112Keys.INSTANCE_KEY);
	boolean performChecks = isPerformTR03112Checks(request);
	if (! performChecks) {
	    logger.warn("Checks according to BSI TR03112 3.4.2, 3.4.4 (TCToken specific) and 3.4.5 are disabled.");
	}
	boolean isObjectActivation = request.getTCTokenURL() == null;
	if (isObjectActivation) {
	    logger.warn("Checks according to BSI TR03112 3.4.4 (TCToken specific) are disabled.");
	}
	dynCtx.put(TR03112Keys.TCTOKEN_CHECKS, performChecks);
	dynCtx.put(TR03112Keys.OBJECT_ACTIVATION, isObjectActivation);
	dynCtx.put(TR03112Keys.TCTOKEN_SERVER_CERTIFICATES, request.getCertificates());
	dynCtx.put(TR03112Keys.TCTOKEN_URL, request.getTCTokenURL());

	ConnectionHandleType connectionHandle = null;
	TCTokenResponse response = new TCTokenResponse();
	response.setRefreshAddress(token.getRefreshAddress());
	response.setCommunicationErrorAddress(token.getCommunicationErrorAddress());

	byte[] requestedContextHandle = request.getContextHandle();
	String ifdName = request.getIFDName();
	BigInteger requestedSlotIndex = request.getSlotIndex();

	if (requestedContextHandle == null || ifdName == null || requestedSlotIndex == null) {
	    // use dumb activation without explicitly specifying the card and terminal
	    // see TR-03112-7 v 1.1.2 (2012-02-28) sec. 3.2
	    connectionHandle = getFirstHandle(request.getCardType());
	} else {
	    // we know exactly which card we want
	    ConnectionHandleType requestedHandle = new ConnectionHandleType();
	    requestedHandle.setContextHandle(requestedContextHandle);
	    requestedHandle.setIFDName(ifdName);
	    requestedHandle.setSlotIndex(requestedSlotIndex);

	    Set<CardStateEntry> matchingHandles = cardStates.getMatchingEntries(requestedHandle);
	    if (! matchingHandles.isEmpty()) {
		connectionHandle = matchingHandles.toArray(new CardStateEntry[] {})[0].handleCopy();
	    }
	}

	if (connectionHandle == null) {
	    String msg = lang.translationForKey("cancel");
	    logger.error(msg);
	    response.setResult(WSHelper.makeResultError(ECardConstants.Minor.SAL.CANCELLATION_BY_USER, msg));
	    return response;
	}

	try {
	    // process binding and follow redirect addresses afterwards
	    response = processBinding(request, connectionHandle);
	    return response;
	} catch (DispatcherException w) {
	    logger.error(w.getMessage(), w);
	    // TODO: check for better matching minor type
	    response.setResult(WSHelper.makeResultError(ECardConstants.Minor.App.INCORRECT_PARM, w.getMessage()));
	    return response;
	} catch (PAOSException w) {
	    logger.error(w.getMessage(), w);
	    Throwable innerException = w.getCause();
	    if (innerException instanceof WSException) {
		response.setResult(((WSException) innerException).getResult());
	    } else {
		// TODO: check for better matching minor type
		response.setResult(WSHelper.makeResultError(ECardConstants.Minor.App.INCORRECT_PARM, w.getMessage()));
	    }
	    return response;
	} finally {
	    // fill in values, so it is usuable by the transport module
	    response = determineRefreshURL(request, response);
	    response.finishResponse();
	}
    }

    private static void waitForTask(Future<?> task) throws PAOSException, DispatcherException {
	try {
	    task.get();
	} catch (InterruptedException ex) {
	    logger.error(ex.getMessage(), ex);
	    throw new PAOSException(ex);
	} catch (ExecutionException ex) {
	    logger.error(ex.getMessage(), ex);
	    // perform conversion of ExecutionException from the Future to the really expected exceptions
	    if (ex.getCause() instanceof PAOSException) {
		throw (PAOSException) ex.getCause();
	    } else if (ex.getCause() instanceof DispatcherException) {
		throw (DispatcherException) ex.getCause();
	    } else {
		throw new PAOSException(ex);
	    }
	}
    }

    /**
     * Follow the URL in the RefreshAddress of the TCToken as long as it is a redirect (302, 303 or 307) AND is a
     * https-URL AND the hash of the retrieved server certificate is contained in the CertificateDescrioption, else
     * return 400. If the URL and the subjectURL in the CertificateDescription conform to the SOP we reached our final
     * destination.
     *
     * @param endpoint current Redirect location
     * @return HTTP redirect to the final address the browser should be redirected to
     */
    private static TCTokenResponse determineRefreshURL(TCTokenRequest request, TCTokenResponse response)
	    throws InvalidRedirect, IOException {
	try {
	    URL endpoint = response.getRefreshAddress();
	    if (endpoint == null) {
		throw new IOException("No endpoint address available for redirect detection.");
	    }
	    DynamicContext dynCtx = DynamicContext.getInstance(TR03112Keys.INSTANCE_KEY);

	    // omit checks completely if this is an object tag activation
	    Object objectActivation = dynCtx.get(TR03112Keys.OBJECT_ACTIVATION);
	    if (objectActivation instanceof Boolean && ((Boolean) objectActivation) == true) {
		return response;
	    }

	    // disable certificate checks according to BSI TR03112-7 in some situations
	    boolean redirectChecks = isPerformTR03112Checks(request);
	    RedirectCertificateValidator verifier = new RedirectCertificateValidator(redirectChecks);
	    ResourceContext ctx = ResourceContext.getStream(endpoint, verifier);

	    // using this verifier no result must be present, meaning no status code different than a redirect occurred
//	    if (result.p1 != null) {
//		// TODO: this error is expected according the spec, handle it in a different way
//		String msg = "Return-To-Websession yielded a non-redirect response.";
//		throw new IOException(msg);
//	    }

	    // determine redirect
	    List<Pair<URL, Certificate>> resultPoints = ctx.getCerts();
	    Pair<URL, Certificate> last = resultPoints.get(resultPoints.size() - 1);
	    endpoint = last.p1;

	    // we finally found the refresh URL; redirect the browser to this location, but first clear context
	    dynCtx.clear();
	    DynamicContext.remove();

	    logger.debug("Setting redirect address to '{}'.", endpoint);
	    response.setRefreshAddress(endpoint);
	    return response;
	} catch (ResourceException | ValidationError ex) {
	    throw new InvalidRedirect(ex.getMessage(), ex);
	}
    }

    private List<String> getSupportedDIDs() {
	TreeSet<String> result = new TreeSet<>();

	// check all sal protocols in the
	AddonRegistry registry = manager.getRegistry();
	Set<AddonSpecification> addons = registry.listAddons();
	for (AddonSpecification addon : addons) {
	    for (ProtocolPluginSpecification proto : addon.getSalActions()) {
		result.add(proto.getUri());
	    }
	}

	return new ArrayList<>(result);
    }

    /**
     * Checks if checks according to BSI TR03112-7 3.4.2, 3.4.4 and 3.4.5 must be performed.
     *
     * @param tcTokenRequest TC Token request.
     * @return {@code true} if checks should be performed, {@code false} otherwise.
     */
    private static boolean isPerformTR03112Checks(TCTokenRequest tcTokenRequest) {
	boolean activationChecks = true;
	String refreshAddress = tcTokenRequest.getTCToken().getRefreshAddress();
	URL tokenUrl = tcTokenRequest.getTCTokenURL();
	// disable checks when not using the nPA
	if (! tcTokenRequest.getCardType().equals("http://bsi.bund.de/cif/npa.xml")) {
	    activationChecks = false;
	// disable checks when using test servers with wrong certificates
	} else if (refreshAddress.startsWith("https://eservice.openecard.org")) {
	    activationChecks = false;
	} else if (tokenUrl != null) {
	    String tokenUrlStr = tokenUrl.toString();
	    if (tokenUrlStr.startsWith("https://mtg.as.skidentity.de")) {
		activationChecks = false;
	    }
	}
	return activationChecks;
    }

}