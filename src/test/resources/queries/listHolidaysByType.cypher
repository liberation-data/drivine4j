MATCH (h:Holiday)
  WHERE h.type = $type AND h.createdBy = 'test'
RETURN properties(h)