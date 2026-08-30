package bime.dto;

/** How a barcode's data is encoded as bars. EAN13/UPC_A/EAN8 carry a GTIN and are validated by
  * recomputing their check digit; CODE128/CODE39 carry an arbitrary string and are stored opaquely. */
public enum BarcodeSymbology {
    EAN13,
    UPC_A,
    EAN8,
    CODE128,
    CODE39
}
