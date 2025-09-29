MATCH (h:Holiday)
  WHERE h.country = $country AND h.createdBy = 'test'
RETURN properties(h)