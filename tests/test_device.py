from rpa_tt.device import dump_hierarchy_with_retry, parse_validated_network


def test_parse_validated_physical_network_ignores_stale_vpn() -> None:
    stale_vpn = (
        "NetworkAgentInfo{ ni{VPN CONNECTED} "
        "nc{[ Transports: VPN Capabilities: INTERNET&VALIDATED ]}}"
    )
    wifi_vpn = (
        "NetworkAgentInfo{ ni{VPN CONNECTED} "
        "nc{[ Transports: WIFI|VPN Capabilities: INTERNET&VALIDATED ]}}"
    )
    assert not parse_validated_network(stale_vpn)
    assert parse_validated_network(wifi_vpn)


def test_dump_hierarchy_restarts_uiautomator_before_retry() -> None:
    class FakeDevice:
        def __init__(self) -> None:
            self.dumps = 0
            self.restarts = 0

        def dump_hierarchy(self, *, compressed: bool) -> str:
            assert compressed is False
            self.dumps += 1
            if self.dumps == 1:
                raise ConnectionError("temporary disconnect")
            return "<hierarchy />"

        def start_uiautomator(self) -> None:
            self.restarts += 1

    device = FakeDevice()
    assert dump_hierarchy_with_retry(device, retry_delay=0) == "<hierarchy />"
    assert device.dumps == 2
    assert device.restarts == 1
