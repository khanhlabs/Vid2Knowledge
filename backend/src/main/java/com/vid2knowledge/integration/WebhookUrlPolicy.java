package com.vid2knowledge.integration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class WebhookUrlPolicy {
    public URI requirePublicHttps(String candidate) {
        URI uri;
        try {
            uri = URI.create(candidate == null ? "" : candidate.trim());
        } catch (IllegalArgumentException failure) {
            throw rejected();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw rejected();
        }
        assertPublicHost(uri.getHost());
        return uri;
    }

    private void assertPublicHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw rejected();
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || isCarrierGradeNat(address.getAddress())
                        || isUniqueLocalIpv6(address.getAddress())) {
                    throw rejected();
                }
            }
        } catch (UnknownHostException failure) {
            throw rejected();
        }
    }

    private static boolean isCarrierGradeNat(byte[] address) {
        return address.length == 4 && (address[0] & 0xff) == 100
                && ((address[1] & 0xff) >= 64 && (address[1] & 0xff) <= 127);
    }

    private static boolean isUniqueLocalIpv6(byte[] address) {
        return address.length == 16 && ((address[0] & 0xfe) == 0xfc);
    }

    private static ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook URL must be a resolvable public HTTPS endpoint on port 443");
    }
}
