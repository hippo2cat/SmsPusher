package com.hippo2cat.smspusher.discovery;

import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MacEndpointUrls {
    private MacEndpointUrls() {}

    public static String baseUrl(NsdServiceInfo service) {
        if (service == null) return null;
        return baseUrl(service.getAttributes(), service.getHost(), service.getPort());
    }

    public static String baseUrl(Map<String, byte[]> attributes, InetAddress host, int port) {
        if (port <= 0) return null;
        String txtIpv4 = txtIpv4(attributes);
        if (txtIpv4 != null) return "http://" + txtIpv4 + ":" + port;
        return baseUrl(host, port);
    }

    public static String baseUrl(InetAddress host, int port) {
        if (!isUsableHost(host) || port <= 0) return null;
        String address = host.getHostAddress();
        if (address == null || address.isEmpty()) return null;
        if (host instanceof Inet6Address) {
            return "http://[" + address.replace("%", "%25") + "]:" + port;
        }
        return "http://" + address + ":" + port;
    }

    public static String displayAddress(NsdServiceInfo service) {
        if (service == null) return "";
        String txtIpv4 = txtIpv4(service.getAttributes());
        if (txtIpv4 != null && service.getPort() > 0) return txtIpv4 + ":" + service.getPort();
        return displayAddress(service.getHost(), service.getPort());
    }

    public static String displayAddress(InetAddress host, int port) {
        if (host == null) return "";
        String address = host.getHostAddress();
        return address == null || address.isEmpty() ? "" : address + ":" + port;
    }

    public static boolean isUsableHost(InetAddress host) {
        if (host == null) return false;
        return host instanceof Inet4Address || host instanceof Inet6Address;
    }

    private static String txtIpv4(Map<String, byte[]> attributes) {
        if (attributes == null) return null;
        byte[] value = attributes.get("ipv4");
        if (value == null || value.length == 0) return null;
        String candidate = new String(value, StandardCharsets.UTF_8).trim();
        if (candidate.isEmpty()) return null;
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (address instanceof Inet4Address) return address.getHostAddress();
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
