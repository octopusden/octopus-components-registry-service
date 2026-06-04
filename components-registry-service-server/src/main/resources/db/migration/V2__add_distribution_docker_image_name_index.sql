-- Backs the cross-component image-name uniqueness check with an indexed
-- equality lookup on every component create/update.
CREATE INDEX idx_dist_docker_image_name ON distribution_docker_images(image_name);
