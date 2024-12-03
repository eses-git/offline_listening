import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'offline_listening_platform_interface.dart';

/// An implementation of [OfflineListeningPlatform] that uses method channels.
class MethodChannelOfflineListening extends OfflineListeningPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('offline_listening');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
