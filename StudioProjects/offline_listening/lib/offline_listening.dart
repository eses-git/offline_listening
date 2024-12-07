import 'package:flutter/services.dart';

class OfflineListening {
  static const MethodChannel _channel = MethodChannel('flutter_mfcc_plugin');

  /// Checks if the TensorFlow Lite model is loaded correctly.
  /// Returns `true` if the model is loaded successfully, `false` otherwise.
  static Future<bool> isModelLoaded(String modelPath) async {
    try {
      final bool isLoaded = await _channel.invokeMethod(
        'isModelLoaded',
        {'modelPath': modelPath},
      );
      return isLoaded;
    } on PlatformException catch (e) {
      print("Error while checking if model is loaded: ${e.message}");
      return false;
    } catch (e) {
      print("Unexpected error while checking if model is loaded: $e");
      return false;
    }
  }

  /// Starts the listening service with the provided TensorFlow Lite model.
  ///
  /// - [modelPath]: Path to the TensorFlow Lite model.
  ///
  /// This method initializes the wake word detection process using the
  /// provided model. Make sure the model is valid by calling `isModelLoaded`
  /// before invoking this method.
  static Future<void> startListening(String modelPath) async {
    try {
      await _channel.invokeMethod('startListening', {
        'modelPath': modelPath,
      });
      print("Listening started successfully.");
    } on PlatformException catch (e) {
      print("Error while starting listening: ${e.message}");
    } catch (e) {
      print("Unexpected error while starting listening: $e");
    }
  }

  /// Stops the listening service.
  ///
  /// This method halts the wake word detection process and releases resources.
  static Future<void> stopListening() async {
    try {
      await _channel.invokeMethod('stopListening');
      print("Listening stopped successfully.");
    } on PlatformException catch (e) {
      print("Error while stopping listening: ${e.message}");
    } catch (e) {
      print("Unexpected error while stopping listening: $e");
    }
  }
}
