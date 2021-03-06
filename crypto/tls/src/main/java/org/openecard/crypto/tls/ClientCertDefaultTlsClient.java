/****************************************************************************
 * Copyright (C) 2012-2014 ecsec GmbH.
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

package org.openecard.crypto.tls;

import org.openecard.crypto.tls.auth.DynamicAuthentication;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import javax.annotation.Nonnull;
import org.openecard.bouncycastle.crypto.tls.AlertLevel;
import org.openecard.bouncycastle.crypto.tls.CipherSuite;
import org.openecard.bouncycastle.crypto.tls.DefaultTlsClient;
import org.openecard.bouncycastle.crypto.tls.HashAlgorithm;
import org.openecard.bouncycastle.crypto.tls.NamedCurve;
import org.openecard.bouncycastle.crypto.tls.ProtocolVersion;
import org.openecard.bouncycastle.crypto.tls.SignatureAlgorithm;
import org.openecard.bouncycastle.crypto.tls.SignatureAndHashAlgorithm;
import org.openecard.bouncycastle.crypto.tls.TlsAuthentication;
import org.openecard.bouncycastle.crypto.tls.TlsCipherFactory;
import org.openecard.bouncycastle.crypto.tls.TlsECCUtils;
import org.openecard.bouncycastle.crypto.tls.TlsExtensionsUtils;
import org.openecard.bouncycastle.crypto.tls.TlsUtils;
import org.openecard.common.OpenecardProperties;
import org.openecard.crypto.tls.auth.ContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Standard TLS client also implementing the ClientCertTlsClient interface. <br/>
 * If not modified, the TlsAuthentication instance returned by {@link #getAuthentication()} is of type
 * {@link DynamicAuthentication} without further modifications.
 *
 * @author Tobias Wich
 */
public class ClientCertDefaultTlsClient extends DefaultTlsClient implements ClientCertTlsClient {

    private static final Logger logger = LoggerFactory.getLogger(ClientCertDefaultTlsClient.class);

    private final String host;
    private TlsAuthentication tlsAuth;

    /**
     * Create a ClientCertDefaultTlsClient for the given parameters.
     *
     * @param host Host or IP address. Value must not be null.
     * @param doSni Control whether the server should send the SNI Header in the Client Hello.
     */
    public ClientCertDefaultTlsClient(@Nonnull String host, boolean doSni) {
	super(doSni ? host : null);
	boolean tls1 = Boolean.valueOf(OpenecardProperties.getProperty("legacy.tls1"));
	setMinimumVersion(tls1 ? ProtocolVersion.TLSv10 : ProtocolVersion.TLSv11);
	this.host = host;
    }
    /**
     * Create a ClientCertDefaultTlsClient for the given parameters.
     *
     * @param tcf Cipher factory to use in this client.
     * @param host Host or IP address. Value must not be null.
     * @param doSni Control whether the server should send the SNI Header in the Client Hello.
     */
    public ClientCertDefaultTlsClient(@Nonnull TlsCipherFactory tcf, @Nonnull String host, boolean doSni) {
	super(tcf, doSni ? host : null);
	boolean tls1 = Boolean.valueOf(OpenecardProperties.getProperty("legacy.tls1"));
	setMinimumVersion(tls1 ? ProtocolVersion.TLSv10 : ProtocolVersion.TLSv11);
	this.host = host;
    }


    @Override
    public int[] getCipherSuites() {
	return new int[] {
	    // recommended ciphers from TR-02102-2 sec. 3.3.1
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA256,
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA256,
	    // acceptable in case DHE is not available
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384,
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_DH_RSA_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_DH_RSA_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_DH_RSA_WITH_AES_256_CBC_SHA256,
	    CipherSuite.TLS_DH_RSA_WITH_AES_128_CBC_SHA256,
	    // SHA1 is acceptable until 2015
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_ECDH_RSA_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_DH_RSA_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_DH_RSA_WITH_AES_128_CBC_SHA,
	    // TODO: nobody likes DSS, so kick it out in the next release
	    CipherSuite.TLS_DHE_DSS_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_DHE_DSS_WITH_AES_256_CBC_SHA256,
	    CipherSuite.TLS_DHE_DSS_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_DH_DSS_WITH_AES_256_GCM_SHA384,
	    CipherSuite.TLS_DH_DSS_WITH_AES_128_GCM_SHA256,
	    CipherSuite.TLS_DH_DSS_WITH_AES_256_CBC_SHA256,
	    CipherSuite.TLS_DH_DSS_WITH_AES_128_CBC_SHA256,
	    CipherSuite.TLS_DHE_DSS_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
	    CipherSuite.TLS_DH_DSS_WITH_AES_256_CBC_SHA,
	    CipherSuite.TLS_DH_DSS_WITH_AES_128_CBC_SHA,
	};
    }

    @Override
    public synchronized TlsAuthentication getAuthentication() throws IOException {
	if (tlsAuth == null) {
	    tlsAuth = new DynamicAuthentication(host);
	}
	if (tlsAuth instanceof ContextAware) {
	    ((ContextAware) tlsAuth).setContext(context);
	}
	return tlsAuth;
    }

    @Override
    public synchronized void setAuthentication(TlsAuthentication tlsAuth) {
	this.tlsAuth = tlsAuth;
    }

    @Override
    public Hashtable getClientExtensions() throws IOException {
	Hashtable clientExtensions = super.getClientExtensions();
	clientExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(clientExtensions);

	// code taken from AbstractTlsClient, if that should ever change modify it here too
	if (TlsECCUtils.containsECCCipherSuites(getCipherSuites())) {
            this.namedCurves = new int[] {
		// required parameters TR-03116-4 sec. 4.1.4
		NamedCurve.secp224r1, NamedCurve.secp256r1, NamedCurve.brainpoolP256r1,
		// other possible parameters TR-02102-2 sec. 3.6
		NamedCurve.secp384r1, NamedCurve.brainpoolP384r1, NamedCurve.brainpoolP512r1,
	    };

            TlsECCUtils.addSupportedEllipticCurvesExtension(clientExtensions, namedCurves);
	}

	// overwrite hash and signature algorithms
        if (TlsUtils.isSignatureAlgorithmsExtensionAllowed(clientVersion)) {
            short[] hashAlgorithms = new short[]{ HashAlgorithm.sha512, HashAlgorithm.sha384, HashAlgorithm.sha256,
                HashAlgorithm.sha224, HashAlgorithm.sha1 };

            short[] signatureAlgorithms = new short[]{ SignatureAlgorithm.rsa, SignatureAlgorithm.ecdsa };

            this.supportedSignatureAlgorithms = new Vector();
            for (int i = 0; i < hashAlgorithms.length; ++i) {
                for (int j = 0; j < signatureAlgorithms.length; ++j) {
                    this.supportedSignatureAlgorithms.addElement(new SignatureAndHashAlgorithm(hashAlgorithms[i],
                        signatureAlgorithms[j]));
                }
            }

            /*
             * RFC 5264 7.4.3. Currently, DSA [DSS] may only be used with SHA-1.
             */
            this.supportedSignatureAlgorithms.addElement(new SignatureAndHashAlgorithm(HashAlgorithm.sha1,
                SignatureAlgorithm.dsa));

            clientExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(clientExtensions);

            TlsUtils.addSignatureAlgorithmsExtension(clientExtensions, supportedSignatureAlgorithms);
        }


	return clientExtensions;
    }

    @Override
    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Exception cause) {
	TlsError error = new TlsError(alertLevel, alertDescription, message, cause);
	if (alertLevel == AlertLevel.warning && logger.isInfoEnabled()) {
	    logger.info("TLS warning sent.");
	    logger.info(error.toString());
	} else {
	    logger.error("TLS error sent.");
	    logger.error(error.toString(), cause);
	}

	super.notifyAlertRaised(alertLevel, alertDescription, message, cause);
    }

    @Override
    public void notifyAlertReceived(short alertLevel, short alertDescription) {
	TlsError error = new TlsError(alertLevel, alertDescription);
	if (alertLevel == AlertLevel.warning && logger.isInfoEnabled()) {
	    logger.info("TLS warning received.");
	    logger.info(error.toString());
	} else {
	    logger.error("TLS error received.");
	    logger.error(error.toString());
	}

	super.notifyAlertReceived(alertLevel, alertDescription);
    }

}
