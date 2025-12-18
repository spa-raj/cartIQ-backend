-- PostgreSQL Full-Text Search GIN Index for Products
-- This index significantly speeds up full-text search queries
-- Run this migration on your Cloud SQL PostgreSQL instance

-- Create GIN index on the tsvector expression for fast full-text search
-- This allows queries using to_tsvector/plainto_tsquery to use the index
CREATE INDEX IF NOT EXISTS idx_products_fts
ON products
USING GIN (to_tsvector('english', COALESCE(name, '') || ' ' || COALESCE(description, '') || ' ' || COALESCE(brand, '')));

-- Optional: Create a stored tsvector column for even faster searches
-- Uncomment if you want maximum performance (requires trigger to keep updated)

ALTER TABLE products ADD COLUMN IF NOT EXISTS search_vector tsvector;

UPDATE products SET search_vector =
    to_tsvector('english', COALESCE(name, '') || ' ' || COALESCE(description, '') || ' ' || COALESCE(brand, ''));

CREATE INDEX IF NOT EXISTS idx_products_search_vector ON products USING GIN (search_vector);

CREATE OR REPLACE FUNCTION products_search_vector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', COALESCE(NEW.name, '') || ' ' || COALESCE(NEW.description, '') || ' ' || COALESCE(NEW.brand, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE
ON products FOR EACH ROW EXECUTE FUNCTION products_search_vector_trigger();

-- Verify the index was created
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'products';
