package spiralos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import oshi.hardware.NetworkIF;

/**
 * FAILURE CATEGORY: Platform / OS / library-version dependency.
 *
 * This is a plain JUnit test (no Spring context, runs in milliseconds) so it
 * can be run constantly during development to catch platform-specific
 * breakage immediately, which is exactly the kind of bug that bit us in
 * practice: TelemetrySampler originally called NetworkIF.getNetworkInterface()
 * to detect the loopback adapter, which compiled fine against the dev
 * machine's OSHI version but did not exist in the version actually resolved
 * on the deployment server, crashing the app at startup.
 *
 * MITIGATION IMPLEMENTED: isLoopback() was rewritten to use only fields that
 * are stable across OSHI versions and across Windows/Linux/macOS -
 * interface name, display name, and IPv4 address - instead of a
 * version-specific helper method. These tests assert that logic holds for
 * the real naming conventions each platform actually uses, and that it never
 * throws on the null/empty data a stripped-down OS (like the minimized
 * Ubuntu Pi-hole server) can legitimately return.
 */
class ResilienceStaticLogicTests {

    private NetworkIF nif(String name, String displayName, String[] ipv4) {
        NetworkIF n = mock(NetworkIF.class);
        lenient().when(n.getName()).thenReturn(name);
        lenient().when(n.getDisplayName()).thenReturn(displayName);
        lenient().when(n.getIPv4addr()).thenReturn(ipv4);
        return n;
    }

    @Test
    @DisplayName("Linux loopback ('lo') is detected")
    void linuxLoopback() {
        assertTrue(TelemetrySampler.isLoopback(nif("lo", "lo", new String[] { "127.0.0.1" })));
    }

    @Test
    @DisplayName("macOS loopback ('lo0') is detected")
    void macLoopback() {
        assertTrue(TelemetrySampler.isLoopback(nif("lo0", "lo0", new String[] { "127.0.0.1" })));
    }

    @Test
    @DisplayName("Windows loopback, identified by display name rather than short name, is detected")
    void windowsLoopback() {
        assertTrue(TelemetrySampler.isLoopback(
                nif("net5", "Software Loopback Interface 1", new String[] { "127.0.0.1" })));
    }

    @Test
    @DisplayName("Loopback is still detected purely from its 127.x address when the name gives no hint")
    void loopbackDetectedByAddressAlone() {
        assertTrue(TelemetrySampler.isLoopback(nif("eth9", "Unlabeled Adapter", new String[] { "127.5.5.5" })));
    }

    @Test
    @DisplayName("A real network interface (eth0 / wlan0 style) is never flagged as loopback")
    void realInterfaceNotFlagged() {
        assertFalse(TelemetrySampler.isLoopback(nif("eth0", "Ethernet", new String[] { "192.168.50.113" })));
        assertFalse(TelemetrySampler.isLoopback(nif("wlan0", "Wi-Fi", new String[] { "10.0.0.5" })));
    }

    @Test
    @DisplayName("Interfaces with no IPv4 address assigned (common on a minimal server image) do not crash")
    void noAddressDoesNotThrow() {
        assertFalse(TelemetrySampler.isLoopback(nif("eth1", "Unconfigured", new String[0])));
        assertFalse(TelemetrySampler.isLoopback(nif("eth2", "Unconfigured", null)));
    }

    @Test
    @DisplayName("Null name/display name from a stripped-down OS image does not throw")
    void nullFieldsDoNotThrow() {
        assertFalse(TelemetrySampler.isLoopback(nif(null, null, new String[] { "192.168.50.50" })));
    }

    // --- AppSettings: prevents a bad value (from any platform's properties
    // file format quirks, or a malicious PUT body) from putting the UI into
    // a nonsensical or divide-by-zero state. ---

    @Test
    @DisplayName("Default settings are always valid")
    void defaultsAreValid() {
        AppSettings.defaults().validate();
    }

    @Test
    @DisplayName("A warning threshold at or above the critical threshold is rejected")
    void warnAboveCriticalRejected() {
        AppSettings bad = new AppSettings(2000, 90, 85, 75, 90, 8, 100, 200);
        assertThrows(IllegalArgumentException.class, bad::validate);
    }

    @Test
    @DisplayName("An out-of-range refresh interval is rejected rather than silently accepted")
    void outOfRangeRefreshRejected() {
        AppSettings bad = new AppSettings(100, 70, 85, 75, 90, 8, 100, 200);
        assertThrows(IllegalArgumentException.class, bad::validate);
    }
}
