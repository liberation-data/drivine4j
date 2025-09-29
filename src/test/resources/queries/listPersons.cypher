MATCH (p:Person)
  WHERE p.city = $city AND p.createdBy = 'test'
RETURN properties(p)