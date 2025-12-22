-- Add category name to FTS search_vector for better search relevance
-- This ensures searches like "Samsung smartphones" match products in the Smartphones category

-- Drop existing trigger first
DROP TRIGGER IF EXISTS tsvectorupdate ON products;

-- Update the trigger function to include category name
CREATE OR REPLACE FUNCTION products_search_vector_trigger() RETURNS trigger AS $$
DECLARE
    category_name TEXT;
BEGIN
    -- Get category name for the product
    SELECT c.name INTO category_name
    FROM categories c
    WHERE c.id = NEW.category_id;

    -- Build search vector with category name included
    -- Using setweight to give higher importance to name and category
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(category_name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');

    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- Recreate the trigger
CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE
ON products FOR EACH ROW EXECUTE FUNCTION products_search_vector_trigger();

-- Update all existing products to include category in search_vector
UPDATE products p SET search_vector =
    setweight(to_tsvector('english', COALESCE(p.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(c.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(p.brand, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(p.description, '')), 'C')
FROM categories c
WHERE p.category_id = c.id;

-- Verify the update
SELECT COUNT(*) as updated_products FROM products WHERE search_vector IS NOT NULL;
