import 'package:flutter_test/flutter_test.dart';
import 'package:offline_listening/offline_listening.dart';
import 'package:offline_listening/offline_listening_platform_interface.dart';
import 'package:offline_listening/offline_listening_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockOfflineListeningPlatform
    with MockPlatformInterfaceMixin
    implements OfflineListeningPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final OfflineListeningPlatform initialPlatform = OfflineListeningPlatform.instance;

  test('$MethodChannelOfflineListening is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelOfflineListening>());
  });

  test('getPlatformVersion', () async {
    OfflineListening offlineListeningPlugin = OfflineListening();
    MockOfflineListeningPlatform fakePlatform = MockOfflineListeningPlatform();
    OfflineListeningPlatform.instance = fakePlatform;

    expect(await offlineListeningPlugin.getPlatformVersion(), '42');
  });
}
