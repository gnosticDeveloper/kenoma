package bime.dto;

/** Where a variant's barcode came from. PROVIDER: scanned off manufacturer-supplied goods.
  * ISSUED: minted by this system from the org's barcode issuance settings. */
public enum BarcodeSource {
    PROVIDER,
    ISSUED
}
