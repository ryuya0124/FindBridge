import Foundation

// Fixed public integers only; return the representation of eight copied bytes.
@_cdecl("referenceUInt64DataWords")
public func referenceUInt64DataWords(_ value: UInt64, _ output: UnsafeMutablePointer<UInt64>) {
  var source = value
  let data = withUnsafeBytes(of: &source) { Data($0) }
  withUnsafeBytes(of: data) { bytes in
    precondition(bytes.count == 16)
    output[0] = bytes.loadUnaligned(fromByteOffset: 0, as: UInt64.self)
    output[1] = bytes.loadUnaligned(fromByteOffset: 8, as: UInt64.self)
  }
}
