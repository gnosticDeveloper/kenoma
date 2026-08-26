-- Wire up product_metadata_option.code as the SKU fragment source, and make
-- product_variants.sku a fully generated, unique field instead of an optional
-- client-supplied one falling back to the product's sku at read time.

-- 1) Backfill code for existing rows that never had one.
UPDATE product_metadata_option
SET code = left(upper(regexp_replace(value, '[^a-zA-Z0-9]', '', 'g')), 50)
WHERE code IS NULL;

UPDATE product_metadata_option
SET code = 'OPT'
WHERE code IS NULL OR code = '';

-- 2) De-duplicate codes within the same metadata definition. Bounded loop: each
-- pass keeps the earliest row in a duplicate (metadata_id, code) group as-is
-- and appends its rank to every later row's code, until no duplicates remain.
DO $$
DECLARE
    remaining integer;
    iterations integer := 0;
BEGIN
    LOOP
        WITH ranked AS (
            SELECT id, code,
                   row_number() OVER (PARTITION BY metadata_id, code ORDER BY created_at, id) AS rn
            FROM product_metadata_option
        )
        UPDATE product_metadata_option pmo
        SET code = left(ranked.code || ranked.rn::text, 50)
        FROM ranked
        WHERE pmo.id = ranked.id AND ranked.rn > 1;

        SELECT count(*) INTO remaining
        FROM (
            SELECT metadata_id, code
            FROM product_metadata_option
            GROUP BY metadata_id, code
            HAVING count(*) > 1
        ) dups;

        iterations := iterations + 1;
        EXIT WHEN remaining = 0 OR iterations >= 20;
    END LOOP;
END $$;

ALTER TABLE product_metadata_option ALTER COLUMN code SET NOT NULL;
ALTER TABLE product_metadata_option ADD CONSTRAINT uq_product_metadata_option_code UNIQUE (metadata_id, code);

-- 3) Backfill product_variants.sku for existing rows using the same
-- product-sku + ordered-option-codes convention the application now generates.
UPDATE product_variants pv
SET sku = sub.generated_sku
FROM (
    SELECT pv2.id,
           p.sku || COALESCE('-' || string_agg(pmo.code, '-' ORDER BY pm.name, pmo.code), '') AS generated_sku
    FROM product_variants pv2
    JOIN products p ON p.id = pv2.product_id
    LEFT JOIN product_variant_options pvo ON pvo.variant_id = pv2.id
    LEFT JOIN product_metadata_option pmo ON pmo.id = pvo.option_id
    LEFT JOIN product_metadata pm ON pm.id = pmo.metadata_id
    WHERE pv2.sku IS NULL
    GROUP BY pv2.id, p.sku
) sub
WHERE pv.id = sub.id;

-- De-duplicate any resulting same-org sku collisions (e.g. multiple zero-option
-- variants of the same product carried over from before duplicate-combo checks).
DO $$
DECLARE
    remaining integer;
    iterations integer := 0;
BEGIN
    LOOP
        WITH ranked AS (
            SELECT id, sku,
                   row_number() OVER (PARTITION BY org_id, sku ORDER BY created_at, id) AS rn
            FROM product_variants
        )
        UPDATE product_variants pv
        SET sku = left(ranked.sku || '_' || ranked.rn::text, 255)
        FROM ranked
        WHERE pv.id = ranked.id AND ranked.rn > 1;

        SELECT count(*) INTO remaining
        FROM (
            SELECT org_id, sku
            FROM product_variants
            GROUP BY org_id, sku
            HAVING count(*) > 1
        ) dups;

        iterations := iterations + 1;
        EXIT WHEN remaining = 0 OR iterations >= 20;
    END LOOP;
END $$;

ALTER TABLE product_variants ALTER COLUMN sku SET NOT NULL;
ALTER TABLE product_variants ADD CONSTRAINT uq_product_variants_org_sku UNIQUE (org_id, sku);
